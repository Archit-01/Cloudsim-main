package cloudsim.simulations;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.*;

import java.util.*;

public class CostOptimizationSimulation {

    private static final class VMType {
        String name;
        int mips;
        int pes;
        int ram;
        double hourlyCost;

        VMType(String name, int mips, int pes, int ram, double hourlyCost) {
            this.name = name;
            this.mips = mips;
            this.pes = pes;
            this.ram = ram;
            this.hourlyCost = hourlyCost;
        }
    }

    private static final VMType[] VM_TYPES = {
        new VMType("Small", 500, 1, 512, 0.05),
        new VMType("Medium", 1000, 2, 1024, 0.10),
        new VMType("Large", 2000, 4, 2048, 0.20)
    };

    public static void main(String[] args) {
        System.out.println("Starting Cost Optimization Simulation...");

        try {
            testStrategy("Cheapest-First", CostOptimizationSimulation::cheapestFirst);
            testStrategy("Performance-First", CostOptimizationSimulation::performanceFirst);
            testStrategy("Balanced", CostOptimizationSimulation::balancedApproach);

            System.out.println("\nSimulation completed!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private interface AllocationStrategy {
        List<Vm> allocateVMs(int brokerId);
    }

    private static void testStrategy(String strategyName, AllocationStrategy strategy) throws Exception {
        System.out.println("\n=== Testing Strategy: " + strategyName + " ===");

        CloudSim.init(1, Calendar.getInstance(), false);

        Datacenter datacenter = createDatacenter("CostAware-DC");
        if (datacenter == null) return;

        DatacenterBroker broker = createBroker();
        if (broker == null) return;

        int brokerId = broker.getId();

        List<Vm> vmList = strategy.allocateVMs(brokerId);
        List<Cloudlet> cloudletList = createWorkloads(brokerId, 20);

        broker.submitVmList(vmList);
        broker.submitCloudletList(cloudletList);

        CloudSim.startSimulation();

        List<Cloudlet> finishedCloudlets = broker.getCloudletReceivedList();
        double totalCost = calculateTotalCost(vmList, finishedCloudlets);
        double avgCompletionTime = calculateAvgCompletionTime(finishedCloudlets);

        System.out.printf("\nResults - Cost: $%.2f | Avg Time: %.2f sec | VMs Used: %d\n",
                totalCost, avgCompletionTime, vmList.size());

        System.out.println("\nDetailed Cloudlet Results:");
        System.out.println("CloudletID\tStatus\tVM ID\tTime\tStart\tFinish");
        for (Cloudlet cloudlet : finishedCloudlets) {
            System.out.printf("%d\t\t%s\t%d\t%.2f\t%.2f\t%.2f\n",
                    cloudlet.getCloudletId(),
                    cloudlet.getStatus() == Cloudlet.SUCCESS ? "SUCCESS" : "FAILED",
                    cloudlet.getVmId(),
                    cloudlet.getActualCPUTime(),
                    cloudlet.getExecStartTime(),
                    cloudlet.getFinishTime());
        }

        CloudSim.terminateSimulation();
    }

    private static List<Vm> cheapestFirst(int brokerId) {
        List<Vm> vms = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            vms.add(createVM(brokerId, i, VM_TYPES[0]));
        }
        return vms;
    }

    private static List<Vm> performanceFirst(int brokerId) {
        List<Vm> vms = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            vms.add(createVM(brokerId, i, VM_TYPES[2]));
        }
        return vms;
    }

    private static List<Vm> balancedApproach(int brokerId) {
        List<Vm> vms = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            VMType type = (i % 2 == 0) ? VM_TYPES[1] : VM_TYPES[0];
            vms.add(createVM(brokerId, i, type));
        }
        return vms;
    }

    private static Vm createVM(int brokerId, int id, VMType type) {
        long size = 10000; // 10GB
        int bw = 1000;     // Bandwidth
        return new Vm(id, brokerId, type.mips, type.pes, type.ram, bw, size,
                "Xen", new CloudletSchedulerTimeShared());
    }

    private static Datacenter createDatacenter(String name) {
        List<Host> hostList = new ArrayList<>();
        List<Pe> peList = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            peList.add(new Pe(i, new PeProvisionerSimple(1000)));
        }

        hostList.add(new Host(
                0,
                new RamProvisionerSimple(16384),
                new BwProvisionerSimple(10000),
                1000000,
                peList,
                new VmSchedulerTimeShared(peList)));

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList,
                10.0, 3.0, 0.05, 0.001, 0.0);

        try {
            return new Datacenter(name, characteristics,
                    new VmAllocationPolicySimple(hostList),
                    new LinkedList<>(), 0);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static DatacenterBroker createBroker() {
        try {
            return new DatacenterBroker("CostAware-Broker");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static List<Cloudlet> createWorkloads(int brokerId, int count) {
        List<Cloudlet> cloudlets = new ArrayList<>();
        UtilizationModel utilizationModel = new UtilizationModelFull();

        for (int i = 0; i < count; i++) {
            long length = 1000 + (i % 3) * 500;
            Cloudlet cloudlet = new Cloudlet(i, length, 1, 300, 300,
                    utilizationModel, utilizationModel, utilizationModel);
            cloudlet.setUserId(brokerId);
            cloudlets.add(cloudlet);
        }
        return cloudlets;
    }

    private static double calculateTotalCost(List<Vm> vms, List<Cloudlet> cloudlets) {
        double totalCost = 0;

        for (Vm vm : vms) {
            Optional<VMType> typeOpt = Arrays.stream(VM_TYPES)
                    .filter(t -> t.mips == vm.getMips() && t.ram == vm.getRam())
                    .findFirst();

            if (typeOpt.isPresent()) {
                double maxTime = cloudlets.stream()
                        .filter(c -> c.getVmId() == vm.getId())
                        .mapToDouble(Cloudlet::getFinishTime)
                        .max().orElse(0);
                totalCost += typeOpt.get().hourlyCost * (maxTime / 3600.0);
            }
        }
        return totalCost;
    }

    private static double calculateAvgCompletionTime(List<Cloudlet> cloudlets) {
        return cloudlets.stream()
                .mapToDouble(Cloudlet::getActualCPUTime)
                .average()
                .orElse(0.0);
    }
}