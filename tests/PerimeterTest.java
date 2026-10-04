import dev.tide.spawnselector.Perimeter;
import java.util.*;

public class PerimeterTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Random random = new Random(72119);
        for (int i=0; i<10000; i++) {
            int minX = random.nextInt(50_000_000)-25_000_000;
            int minZ = random.nextInt(50_000_000)-25_000_000;
            int maxX = minX+random.nextInt(8192), maxZ=minZ+random.nextInt(8192);
            String side = List.of("north","east","south","west").get(i%4);
            var points = Perimeter.candidates(minX,minZ,maxX,maxZ,8,48,side);
            check(!points.isEmpty() && points.size() <=512, "Candidate budget");
            check(new HashSet<>(points).size() == points.size(), "No duplicates");
            for (var p : points) {
                int dx = Math.max(Math.max(minX-p.x(),p.x()-maxX),0);
                int dz = Math.max(Math.max(minZ-p.z(),p.z()-maxZ),0);
                int distance = Math.max(dx,dz);
                check(distance >= 8 && distance <=48, "Always outside the full structure footprint");
            }
            var p = points.get(0);
            var dense=Perimeter.refine(minX,minZ,maxX,maxZ,8,48,points.subList(0,Math.min(8,points.size())),points);
            check(dense.size()<=256,"Dense budget is independent of footprint dimensions");
            check(dense.stream().noneMatch(points::contains),"Dense phase does not repeat sparse samples");
            check(new HashSet<>(dense).size()==dense.size(),"Dense samples unique");
            for(var point:dense) {
                int dx=Math.max(Math.max(minX-point.x(),point.x()-maxX),0);
                int dz=Math.max(Math.max(minZ-point.z(),point.z()-maxZ),0);
                check(Math.max(dx,dz)>=8 && Math.max(dx,dz)<=48,"Dense samples remain outside at configured distance");
            }
            check(switch(side) {
                case "north" -> p.z() == minZ-8;
                case "south" -> p.z() == maxZ+8;
                case "east" -> p.x() == maxX+8;
                default -> p.x() == minX-8;
            }, "Preferred side first");
        }
        check(Perimeter.candidates(0,0,200,200,4,128,"north").size()<=512,"Hard cap for many rings");
        var sparse=Perimeter.candidates(0,0,4096,4096,8,48,"north");
        var dense=Perimeter.refine(0,0,4096,4096,8,48,List.of(sparse.get(0)),sparse);
        check(dense.contains(new Perimeter.Point(sparse.get(0).x()+1,sparse.get(0).z())),"Fallback reaches a narrow safe gap missed by sparse samples");
        System.out.println("PASS: 10000 randomized footprints, negative coordinates, mega structures, preferred sides, uniqueness and budgets");
    }
}
