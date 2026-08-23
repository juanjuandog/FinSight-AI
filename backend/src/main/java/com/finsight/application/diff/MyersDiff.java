package com.finsight.application.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal Myers shortest-edit-script implementation for deterministic report diffs.
 */
public final class MyersDiff {
    private MyersDiff() {
    }

    public static <T> List<Edit<T>> diff(List<T> before, List<T> after) {
        List<T> left = before == null ? List.of() : List.copyOf(before);
        List<T> right = after == null ? List.of() : List.copyOf(after);
        if (left.isEmpty()) {
            return right.stream().map(value -> new Edit<>(Operation.INSERT, value)).toList();
        }
        if (right.isEmpty()) {
            return left.stream().map(value -> new Edit<>(Operation.DELETE, value)).toList();
        }

        int max = left.size() + right.size();
        Map<Integer, Integer> frontier = new HashMap<>();
        frontier.put(1, 0);
        List<Map<Integer, Integer>> trace = new ArrayList<>();

        for (int distance = 0; distance <= max; distance++) {
            trace.add(new HashMap<>(frontier));
            for (int diagonal = -distance; diagonal <= distance; diagonal += 2) {
                int x;
                if (diagonal == -distance || (diagonal != distance
                        && coordinate(frontier, diagonal - 1) < coordinate(frontier, diagonal + 1))) {
                    x = coordinate(frontier, diagonal + 1);
                } else {
                    x = coordinate(frontier, diagonal - 1) + 1;
                }
                int y = x - diagonal;
                while (x < left.size() && y < right.size() && Objects.equals(left.get(x), right.get(y))) {
                    x++;
                    y++;
                }
                frontier.put(diagonal, x);
                if (x >= left.size() && y >= right.size()) {
                    return backtrack(trace, left, right, distance);
                }
            }
        }
        throw new IllegalStateException("Unable to calculate report diff");
    }

    private static int coordinate(Map<Integer, Integer> frontier, int diagonal) {
        return frontier.getOrDefault(diagonal, 0);
    }

    private static <T> List<Edit<T>> backtrack(
            List<Map<Integer, Integer>> trace,
            List<T> before,
            List<T> after,
            int distance
    ) {
        int x = before.size();
        int y = after.size();
        List<Edit<T>> reversed = new ArrayList<>();

        for (int currentDistance = distance; currentDistance > 0; currentDistance--) {
            Map<Integer, Integer> frontier = trace.get(currentDistance);
            int diagonal = x - y;
            int previousDiagonal;
            if (diagonal == -currentDistance || (diagonal != currentDistance
                    && coordinate(frontier, diagonal - 1) < coordinate(frontier, diagonal + 1))) {
                previousDiagonal = diagonal + 1;
            } else {
                previousDiagonal = diagonal - 1;
            }

            int previousX = coordinate(frontier, previousDiagonal);
            int previousY = previousX - previousDiagonal;
            while (x > previousX && y > previousY) {
                reversed.add(new Edit<>(Operation.EQUAL, before.get(x - 1)));
                x--;
                y--;
            }
            if (x == previousX) {
                reversed.add(new Edit<>(Operation.INSERT, after.get(y - 1)));
                y--;
            } else {
                reversed.add(new Edit<>(Operation.DELETE, before.get(x - 1)));
                x--;
            }
        }

        while (x > 0 && y > 0) {
            reversed.add(new Edit<>(Operation.EQUAL, before.get(x - 1)));
            x--;
            y--;
        }
        while (x > 0) {
            reversed.add(new Edit<>(Operation.DELETE, before.get(--x)));
        }
        while (y > 0) {
            reversed.add(new Edit<>(Operation.INSERT, after.get(--y)));
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    public record Edit<T>(Operation operation, T value) {
    }

    public enum Operation {
        EQUAL,
        INSERT,
        DELETE
    }
}
