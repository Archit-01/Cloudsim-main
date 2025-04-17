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
        double costPerSecond;

        VMType(String name, int mips, int pes, int ram, double costPerHour) {
            this.name = name;
            this.mips = mips;
            this.pes = pes;
            this.ram = ram;
            this.costPerSecond = costPerHour / 3600;
        }
    }

    // VM configurations with more realistic price-to-performance ratios
    // Adjusted to make larger VMs disproportionately more expensive
    private static final VMType[] VM_TYPES = {
        new VMType("Small", 500, 1, 512, 0.05),    // $0.05/hour
        new VMType("Medium", 1000, 2, 1024, 0.15), // $0.15/hour (2x performance for 3x price)
        new VMType("Large", 2000, 4, 2048, 0.40)   // $0.40/hour (4x performance for 8x price)
    };

    public static void main(String[] args) {
        System.out.println("Starting Cost Optimization Simulation...");
        try {
            testStrategy("Economy (Many Small VMs)", CostOptimizationSimulation::cheapestFirst);
            testStrategy("Balanced (Mixed VMs)", CostOptimizationSimulation::balancedApproach);
            testStrategy("Performance (Few Large VMs)", CostOptimizationSimulation::performanceFirst);
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
        DatacenterBroker broker = createBroker();
        int brokerId = broker.getId();

        List<Vm> vmList = strategy.allocateVMs(brokerId);
        List<Cloudlet> cloudletList = createWorkloads(brokerId, 40);

        broker.submitVmList(vmList);
        broker.submitCloudletList(cloudletList);

        CloudSim.startSimulation();

        List<Cloudlet> finishedCloudlets = broker.getCloudletReceivedList();
        double totalCost = calculateTotalCost(vmList, finishedCloudlets);
        double avgCompletionTime = calculateAvgCompletionTime(finishedCloudlets);

        System.out.printf("\nResults - Cost: $%.4f | Avg Time: %.2f sec | VMs Used: %d\n",
                totalCost, avgCompletionTime, vmList.size());

        CloudSim.terminateSimulation();
    }

    private static List<Vm> cheapestFirst(int brokerId) {
        List<Vm> vms = new ArrayList<>();
        // Use more small VMs - cheaper per hour but takes longer
        for (int i = 0; i < 10; i++) {
            vms.add(createVM(brokerId, i, VM_TYPES[0])); // Small VMs
        }
        return vms;
    }

    private static List<Vm> performanceFirst(int brokerId) {
        List<Vm> vms = new ArrayList<>();
        // Use fewer large VMs - faster but more expensive per hour
        for (int i = 0; i < 4; i++) {
            vms.add(createVM(brokerId, i, VM_TYPES[2])); // Large VMs
        }
        return vms;
    }

    private static List<Vm> balancedApproach(int brokerId) {
        List<Vm> vms = new ArrayList<>();
        // Use a mix of VM sizes for a balanced approach
        // 2 Medium VMs
        vms.add(createVM(brokerId, 0, VM_TYPES[1])); 
        vms.add(createVM(brokerId, 1, VM_TYPES[1]));
        
        // 1 Large VM for performance-critical tasks
        vms.add(createVM(brokerId, 2, VM_TYPES[2]));
        
        // 3 Small VMs for less demanding tasks
        vms.add(createVM(brokerId, 3, VM_TYPES[0]));
        vms.add(createVM(brokerId, 4, VM_TYPES[0]));
        vms.add(createVM(brokerId, 5, VM_TYPES[0]));
        
        return vms;
    }

    private static Vm createVM(int brokerId, int id, VMType type) {
        return new Vm(id, brokerId, type.mips, type.pes, type.ram, 1000, 10000,
                "Xen", new CloudletSchedulerTimeShared());
    }

    private static Datacenter createDatacenter(String name) {
        List<Host> hostList = new ArrayList<>();
        List<Pe> peList = new ArrayList<>();

        for (int i = 0; i < 32; i++) {
            peList.add(new Pe(i, new PeProvisionerSimple(2000)));
        }

        hostList.add(new Host(
                0,
                new RamProvisionerSimple(65536),
                new BwProvisionerSimple(100000),
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
        Random rand = new Random(42); // Fixed seed for reproducibility
        
        for (int i = 0; i < count; i++) {
            long length = 5000 + rand.nextInt(10000); // 5000-15000 MI
            Cloudlet cloudlet = new Cloudlet(i, length, 1, 300, 300,
                    utilizationModel, utilizationModel, utilizationModel);
            cloudlet.setUserId(brokerId);
            cloudlets.add(cloudlet);
        }
        return cloudlets;
    }

    private static double calculateTotalCost(List<Vm> vms, List<Cloudlet> cloudlets) {
        double maxFinishTime = cloudlets.stream()
                .mapToDouble(Cloudlet::getFinishTime)
                .max()
                .orElse(0);
        
        double totalCost = 0.0;
        for (Vm vm : vms) {
            Optional<VMType> typeOpt = Arrays.stream(VM_TYPES)
                    .filter(t -> t.mips == vm.getMips() && 
                               t.ram == vm.getRam() && 
                               t.pes == vm.getNumberOfPes())
                    .findFirst();

            if (typeOpt.isPresent()) {
                double vmCost = typeOpt.get().costPerSecond * maxFinishTime;
                totalCost += vmCost;
                
                System.out.printf("VM %d (%s) was allocated for %.2f seconds: $%.4f%n",
                        vm.getId(),
                        typeOpt.get().name,
                        maxFinishTime,
                        vmCost);
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