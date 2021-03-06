package org.jenkinsci.plugins.extremefeedback;

import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.*;
import hudson.Plugin;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.TopLevelItem;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.extremefeedback.model.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class Lamps extends Plugin {

    private Set<Lamp> lamps = new ConcurrentSkipListSet<Lamp>();
    transient private static final Logger LOGGER = Logger.getLogger("jenkins.plugins.extremefeedback");
    transient private EventBus eventBus = new EventBus("extreme-feedback");
    private XfEventMessage xfEventMessage = new XfEventMessage();

    @Override
    public void start() throws Exception {
        load();
        EventMessageHandler.getInstance().start();
        ZeroMQMessageHandler.getInstance().start();
    }

    public Set<Lamp> findLamps() {
        ListeningExecutorService service = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        ListenableFuture<TreeSet<Lamp>> listenableFuture = service.submit(new LampFinderCallable());
        Futures.addCallback(listenableFuture, new FutureCallback<TreeSet<Lamp>>() {
            public void onSuccess(TreeSet<Lamp> foundLamps) {
                if (lamps.isEmpty()) {
                    lamps = foundLamps;
                } else {
                    ImmutableSet<Lamp> lampsCopy;

                    // Activate lamps
                    Map<String, Lamp> currentLamps = getLampsAsMap();
                    for (Lamp foundLamp : foundLamps) {
                        if (currentLamps.containsKey(foundLamp.getMacAddress())) {
                            Lamp lamp = currentLamps.get(foundLamp.getMacAddress());
                            lamp.setInactive(false);
                        }
                    }
                    lamps = Sets.newTreeSet(currentLamps.values());

                    // Add new lamps
                    lampsCopy = ImmutableSet.copyOf(lamps);
                    Set<Lamp> newLamps = Sets.difference(foundLamps, lampsCopy);
                    lamps.addAll(newLamps);
                    LOGGER.info("Lamps added: " + Joiner.on(", ").join(newLamps));

                    // Deactivate old lamps
                    lampsCopy = ImmutableSet.copyOf(lamps);
                    Set<Lamp> obsoleteLamps = Sets.difference(lampsCopy, foundLamps);
                    Map<String, Lamp> lampsAsMap = getLampsAsMap();
                    for (Lamp obsoleteLamp : obsoleteLamps) {
                        Lamp lamp = lampsAsMap.get(obsoleteLamp.getMacAddress());
                        lamp.setInactive(true);
                    }
                    lamps = Sets.newTreeSet(lampsAsMap.values());
                    LOGGER.info("Lamps removed: " + Joiner.on(", ").join(obsoleteLamps));

                    // Update other lamps
                    lampsCopy = ImmutableSet.copyOf(lamps);
                    Set<Lamp> remainingLamps = Sets.intersection(foundLamps, lampsCopy);
                    Set<List<Lamp>> product = Sets.cartesianProduct(remainingLamps, lampsCopy);
                    for (List<Lamp> lampList : product) {
                        Lamp newLamp = lampList.get(0);
                        Lamp oldLamp = lampList.get(1);
                        if(newLamp.equals(oldLamp) && !newLamp.getIpAddress().equals(oldLamp.getIpAddress())) {
                            oldLamp.setIpAddress(newLamp.getIpAddress());
                            LOGGER.info("Lamp updated: " + oldLamp);
                        }
                    }
                    try {
                        Jenkins.getInstance().getPlugin(Lamps.class).save();
                    } catch (IOException e) {
                        LOGGER.severe(e.getMessage());
                    }
                }
            }

            public void onFailure(Throwable throwable) {
                LOGGER.severe(Throwables.getStackTraceAsString(throwable));
            }
        });

        while(!listenableFuture.isDone()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                LOGGER.warning(e.getMessage());
            }
        }

        return lamps;
    }

    public Set<Lamp> getLamps() {
        return lamps;
    }

    public void setLamps(Set<Lamp> lamps) {
        this.lamps = lamps;
    }

    public Set<String> getJobs() {
        Set<String> jobs = Sets.newHashSet();
        for (Lamp lamp : lamps) {
            jobs.addAll(lamp.getJobs());
        }
        return jobs;
    }

    public Set<Lamp> getLampsContainingJob(String jobName) {
        Set<Lamp> activeLamps = Sets.newHashSet();
        for (Lamp lamp : lamps) {
            if (lamp.getJobs().contains(jobName)) {
                activeLamps.add(lamp);
            }
        }
        return activeLamps;
    }

    public Set<Lamp> addLampByIp(final String ipAddress) {
        ListeningExecutorService service = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        ListenableFuture<String> listenableFuture = service.submit(new LampConfirmCallable(ipAddress));
        Futures.addCallback(listenableFuture, new FutureCallback<String>() {
            public void onSuccess(String macAddress) {
                Lamp lamp = new Lamp(macAddress, ipAddress);
                if (lamps.contains(lamp)) {
                    lamps.remove(lamp);
                }
                lamps.add(lamp);
            }

            public void onFailure(Throwable throwable) {
                LOGGER.warning(throwable.getMessage());
            }
        });

        while(!listenableFuture.isDone()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                LOGGER.warning(e.getMessage());
            }
        }

        return lamps;
    }

    public void updateAggregateStatus(Lamp lamp) {
        if (lamp.isAggregate()) {
            Result lampResult = Result.SUCCESS;
            for (String lampJob : lamp.getJobs()) {
                TopLevelItem item = Jenkins.getInstance().getItem(lampJob);
                if (item instanceof AbstractProject) {
                    AbstractProject job = (AbstractProject) item;
                    Result lastResult;
                    if (job.getLastBuild() != null) {
                        lastResult = job.getLastBuild().getResult();
                    } else {
                        lastResult = Result.SUCCESS;
                    }

                    if (lastResult.isWorseThan(lampResult)) {
                        lampResult = lastResult;
                    }
                }
            }
            xfEventMessage.sendColorMessage(lamp, lampResult, States.Action.SOLID);
        }
    }

    public void updateJobStatus(Lamp lamp, String jobName){
        TopLevelItem item = Jenkins.getInstance().getItem(jobName);
        Result lastResult = Result.SUCCESS;
        if (item instanceof AbstractProject) {
            AbstractProject job = (AbstractProject) item;
            if (job.getLastBuild() != null) {
                lastResult = job.getLastBuild().getResult();
            }
        }
        xfEventMessage.sendColorMessage(lamp, lastResult, States.Action.SOLID);
        if (lamp.isAggregate()) {
            // Let it blink if the lamp is aggregating the results and they differ
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        updateAggregateStatus(lamp);
        }
    }

    public Map<String, Lamp> getLampsAsMap() {
        Map<String, Lamp> result = Maps.newHashMap();
        for (Lamp lamp : lamps) {
            result.put(lamp.getMacAddress(), lamp);
        }
        return result;
    }

    public String getLampByMacAddress(String macAddress) {
        Map<String, Lamp> lamps = getLampsAsMap();
        Lamp lamp = lamps.get(macAddress);
        return lamp.getIpAddress();
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public static Lamps getInstance() {
        return Jenkins.getInstance().getPlugin(Lamps.class);
    }

    public void addLampByMacAddress(String macAddress) {
        lamps.add(new Lamp(macAddress));
    }
}
