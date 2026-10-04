package dev.tide.spawnselector;

import java.util.*;

/** Pure geometry, deliberately independent of Minecraft for regression tests. */
public final class Perimeter {
    public record Point(int x, int z) {}
    public static List<Point> candidates(int minX, int minZ, int maxX, int maxZ,
                                          int minDistance, int maxDistance, String firstSide) {
        LinkedHashSet<Point> points = new LinkedHashSet<>();
        List<String> sides = new ArrayList<>(List.of("north", "east", "south", "west"));
        Collections.rotate(sides, -sides.indexOf(firstSide));
        // Fixed budget per ring, independent of structure dimensions. Finish the preferred
        // side before moving to the next side, so world generation follows the configured
        // direction instead of touching all four sides for every small offset.
        double[] samples = {0.5, 0.375, 0.625, 0.25, 0.75, 0.125, 0.875, 0.0, 1.0};
        for (int d = minDistance; d <= maxDistance; d += 8) {
            int left = minX - d, right = maxX + d, top = minZ - d, bottom = maxZ + d;
            for (String side : sides) {
                for (double fraction : samples) {
                    int x = (int) Math.round(left + (right - left) * fraction);
                    int z = (int) Math.round(top + (bottom - top) * fraction);
                    points.add(switch (side) {
                        case "north" -> new Point(x, top);
                        case "south" -> new Point(x, bottom);
                        case "east" -> new Point(right, z);
                        default -> new Point(left, z);
                    });
                    if (points.size() >= 512) return List.copyOf(points);
                }
            }
        }
        return List.copyOf(points);
    }
    /** Densify a few promising sampled ring segments, never the entire footprint. */
    public static List<Point> refine(int minX, int minZ, int maxX, int maxZ, int minDistance, int maxDistance,
                                    List<Point> anchors, Collection<Point> alreadyChecked) {
        Set<Point> seen = new HashSet<>(alreadyChecked);
        List<Point> result = new ArrayList<>();
        for (Point anchor : anchors.stream().limit(8).toList()) {
            int distance = distance(anchor, minX, minZ, maxX, maxZ);
            boolean horizontal = anchor.z() == minZ-distance || anchor.z() == maxZ+distance;
            for (int radiusOffset : new int[]{0,-2,2,-4,4}) {
                int d = distance + radiusOffset;
                if (d < minDistance || d > maxDistance) continue;
                for (int offset : new int[]{1,-1,2,-2,3,-3,4,-4,6,-6,8,-8}) {
                    Point point = horizontal
                        ? new Point(anchor.x()+offset, anchor.z() < minZ ? minZ-d : maxZ+d)
                        : new Point(anchor.x() < minX ? minX-d : maxX+d, anchor.z()+offset);
                    if (distance(point,minX,minZ,maxX,maxZ) < minDistance
                        || distance(point,minX,minZ,maxX,maxZ) > maxDistance || !seen.add(point)) continue;
                    result.add(point);
                    if (result.size() >= 256) return List.copyOf(result);
                }
            }
        }
        return List.copyOf(result);
    }
    private static int distance(Point point,int minX,int minZ,int maxX,int maxZ) {
        return Math.max(Math.max(Math.max(minX-point.x(),point.x()-maxX),0),
            Math.max(Math.max(minZ-point.z(),point.z()-maxZ),0));
    }
}
