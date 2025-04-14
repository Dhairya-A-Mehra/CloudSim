package org.cloudbus.cloudsim.examples;

import java.io.FileWriter;
import java.io.IOException;
import org.cloudsimplus.allocationpolicies.VmAllocationPolicySimple;
import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.listeners.EventInfo;
import org.cloudsimplus.listeners.EventListener;
import org.cloudsimplus.power.models.PowerModelHost;
import org.cloudsimplus.power.models.PowerModelHostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.utilizationmodels.UtilizationModel;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.XYLineAnnotation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import java.awt.*;
import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EnergyAwareCloudSimExample {

    private static List<Double> powerOverTime = new ArrayList<>();
    private static List<Double> timeStamps = new ArrayList<>();
    private static final Logger logger = LoggerFactory.getLogger(EnergyAwareCloudSimExample.class);
    private static final int HOSTS = 5;
    private static final int VMS = 10;
    private static final int CLOUDLETS = 20;
    private static final double SCHEDULING_INTERVAL = 1.0;
    private static double totalEnergy = 0.0;
    private static Datacenter datacenter;
    private static final Random random = new Random();

    public static void main(String[] args) {
        System.out.println("Starting Energy-Aware CloudSim Plus Simulation...");
        logger.info("Simulating dynamic workload scaling for energy efficiency.");

        CloudSimPlus simulation = new CloudSimPlus();
        simulation.addOnClockTickListener(createClockTickListener());

        datacenter = createDatacenter(simulation);
        DatacenterBroker broker = new DatacenterBrokerSimple(simulation);

        List<Vm> vmList = createVms();
        List<Cloudlet> cloudletList = createCloudlets();

        broker.submitVmList(vmList);
        assignCloudletsToVms(broker, cloudletList, vmList);

        simulation.addOnClockTickListener(event -> {
            if (event.getTime() == 5.0) {
                Host failedHost = datacenter.getHostList().get(0);
                System.out.println("\nSimulating failure of Host 0 at time 5.0 to measure recovery energy cost.");
                failedHost.setFailed(true);
            }
        });

        simulation.start();
        printResults(broker);
        generatePowerUsageChart();
    }

    private static Datacenter createDatacenter(CloudSimPlus simulation) {
        List<Host> hostList = new ArrayList<>();
        for (int i = 0; i < HOSTS; i++) {
            List<Pe> peList = new ArrayList<>();
            // Increased host capacity
            int pes = 16; // Double the PEs
            long mips = 4000; // Higher MIPS per PE

            for (int j = 0; j < pes; j++) {
                peList.add(new PeSimple(mips));
            }

            PowerModelHost powerModel = new PowerModelHostSimple(300, 75);
            Host host = new HostSimple(131072, 80000000, 80000000, peList); // 128GB RAM
            host.setVmScheduler(new VmSchedulerTimeShared())
                    .setPowerModel(powerModel);
            hostList.add(host);
        }

        return new DatacenterSimple(simulation, hostList, new VmAllocationPolicySimple());
    }

    private static List<Vm> createVms() {
        List<Vm> vmList = new ArrayList<>();
        for (int i = 0; i < VMS; i++) {
            // Increased resources - adjust these values as needed
            long mips = (i % 2 == 0) ? 3000 : 2500;
            int pes = (i % 2 == 0) ? 4 : 2;
            long ram = (i % 3 == 0) ? 32768 : 24576; // 32GB or 24GB RAM
            long bw = 10000; // Increased bandwidth (Mbps)
            long storage = 100000; // Increased storage (MB)

            Vm vm = new VmSimple(mips, pes)
                    .setRam(ram)
                    .setBw(bw)
                    .setSize(storage);
            vm.setCloudletScheduler(new CloudletSchedulerTimeShared());
            vmList.add(vm);
        }
        return vmList;
    }

    private static List<Cloudlet> createCloudlets() {
        List<Cloudlet> cloudletList = new ArrayList<>();
        for (int i = 0; i < CLOUDLETS; i++) {
            // Reduced resource requirements
            long length = 10000 + i * 500;
            int pes = (i % 4 == 0) ? 2 : 1; // Fewer PEs
            long fileSize = 1024; // Smaller input files
            long outputSize = 1024;

            // More conservative utilization
            UtilizationModel utilizationModel = new UtilizationModelDynamic(0.4);

            Cloudlet cloudlet = new CloudletSimple(length, pes, utilizationModel)
                    .setSizes(fileSize)
                    .setUtilizationModelRam(new UtilizationModelDynamic(0.3)) // 30% RAM usage
                    .setUtilizationModelBw(new UtilizationModelDynamic(0.4)); // 40% BW usage
            cloudletList.add(cloudlet);
        }
        return cloudletList;
    }

    private static void assignCloudletsToVms(DatacenterBroker broker,
            List<Cloudlet> cloudletList,
            List<Vm> vmList) {
        for (Cloudlet cloudlet : cloudletList) {
            // Find VM with most available resources
            Vm targetVm = vmList.stream()
                    .min(Comparator.comparingDouble(vm -> vm.getCpuPercentUtilization() * 0.7 +
                            vm.getRam().getPercentUtilization() * 0.3))
                    .orElse(vmList.get(0));

            cloudlet.setVm(targetVm);
            broker.submitCloudlet(cloudlet);
        }
    }

    private static EventListener<EventInfo> createClockTickListener() {
        return eventInfo -> {
            double currentTime = eventInfo.getTime();
            if (currentTime > 0) {
                double currentPower = datacenter.getHostList().stream()
                        .filter(host -> !host.isFailed())
                        .mapToDouble(host -> host.getPowerModel().getPower(host.getCpuPercentUtilization()))
                        .sum();

                totalEnergy += currentPower * SCHEDULING_INTERVAL / 3600.0;
                powerOverTime.add(currentPower);
                timeStamps.add(currentTime);

                if (currentTime % 5 == 0) {
                    System.out.printf("Time: %.1f sec | Power: %.2f W | Active Hosts: %d/%d%n",
                            currentTime, currentPower,
                            datacenter.getHostList().stream().filter(h -> !h.isFailed()).count(),
                            HOSTS);
                }
            }
        };
    }

    private static void generatePowerUsageChart() {
        XYSeries series = new XYSeries("Power Usage (W)");
        for (int i = 0; i < powerOverTime.size(); i++) {
            series.add(timeStamps.get(i), powerOverTime.get(i));
        }

        XYSeriesCollection dataset = new XYSeriesCollection();
        dataset.addSeries(series);

        JFreeChart chart = ChartFactory.createXYLineChart(
                "Energy-Aware CloudSim: Power Usage Over Time",
                "Time (s)",
                "Power (W)",
                dataset);

        XYPlot plot = chart.getXYPlot();
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer();
        renderer.setSeriesPaint(0, Color.RED);
        renderer.setSeriesStroke(0, new BasicStroke(2.0f));

        // Mark host failure at 5s
        plot.addAnnotation(new XYLineAnnotation(
                5.0, plot.getRangeAxis().getRange().getLowerBound(),
                5.0, plot.getRangeAxis().getRange().getUpperBound(),
                new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                        1.0f, new float[] { 6.0f }, 0.0f),
                Color.BLUE));

        try {
            ChartUtils.saveChartAsPNG(new File("energy_usage.png"), chart, 800, 600);
            System.out.println("Power chart saved to energy_usage.png");
        } catch (IOException e) {
            System.err.println("Error saving chart: " + e.getMessage());
        }
    }

    private static void printResults(DatacenterBroker broker) {
        System.out.println("\n========== ENERGY-AWARE SCHEDULING RESULTS ==========");

        // Energy stats
        System.out.printf("Total Energy: %.2f Wh (%.2f kWh)%n", totalEnergy, totalEnergy / 1000);
        System.out.printf("Avg Power: %.2f W%n",
                powerOverTime.stream().mapToDouble(d -> d).average().orElse(0));

        // Performance stats
        List<Cloudlet> finishedCloudlets = broker.getCloudletFinishedList();
        System.out.printf("\nCloudlets Completed: %d/%d (%.1f%%)%n",
                finishedCloudlets.size(), CLOUDLETS,
                (finishedCloudlets.size() / (double) CLOUDLETS) * 100);

        // Export power data
        try (FileWriter writer = new FileWriter("power_data.csv")) {
            writer.write("Time(s),Power(W)\n");
            for (int i = 0; i < powerOverTime.size(); i++) {
                writer.write(timeStamps.get(i) + "," + powerOverTime.get(i) + "\n");
            }
            System.out.println("Power data saved to power_data.csv");
        } catch (IOException e) {
            System.err.println("Failed to save power data: " + e.getMessage());
        }
    }
}