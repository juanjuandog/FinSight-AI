package com.finsight.application.diff;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class MyersDiffTest {

    @Test
    void producesShortestEditScriptForMixedChanges() {
        List<String> before = List.of("评级谨慎", "现金流稳定", "估值偏高", "关注负债");
        List<String> after = List.of("评级积极", "现金流稳定", "盈利改善", "关注负债");

        List<MyersDiff.Edit<String>> edits = MyersDiff.diff(before, after);

        assertThat(edits).containsExactly(
                new MyersDiff.Edit<>(MyersDiff.Operation.DELETE, "评级谨慎"),
                new MyersDiff.Edit<>(MyersDiff.Operation.INSERT, "评级积极"),
                new MyersDiff.Edit<>(MyersDiff.Operation.EQUAL, "现金流稳定"),
                new MyersDiff.Edit<>(MyersDiff.Operation.DELETE, "估值偏高"),
                new MyersDiff.Edit<>(MyersDiff.Operation.INSERT, "盈利改善"),
                new MyersDiff.Edit<>(MyersDiff.Operation.EQUAL, "关注负债")
        );
        assertReconstructs(edits, before, after);
    }

    @Test
    void handlesEmptyAndEqualInputs() {
        assertThat(MyersDiff.diff(List.of(), List.of("新增")))
                .containsExactly(new MyersDiff.Edit<>(MyersDiff.Operation.INSERT, "新增"));
        assertThat(MyersDiff.diff(List.of("删除"), List.of()))
                .containsExactly(new MyersDiff.Edit<>(MyersDiff.Operation.DELETE, "删除"));
        assertThat(MyersDiff.diff(List.of("不变"), List.of("不变")))
                .containsExactly(new MyersDiff.Edit<>(MyersDiff.Operation.EQUAL, "不变"));
    }

    @Test
    void reconstructsAndRemainsMinimalAcrossDeterministicRandomFixtures() {
        Random random = new Random(50);
        for (int fixture = 0; fixture < 250; fixture++) {
            List<String> before = randomSequence(random);
            List<String> after = randomSequence(random);

            List<MyersDiff.Edit<String>> edits = MyersDiff.diff(before, after);

            assertReconstructs(edits, before, after);
            long changed = edits.stream().filter(edit -> edit.operation() != MyersDiff.Operation.EQUAL).count();
            assertThat(changed).isEqualTo(before.size() + after.size() - 2L * longestCommonSubsequence(before, after));
        }
    }

    private void assertReconstructs(
            List<MyersDiff.Edit<String>> edits,
            List<String> before,
            List<String> after
    ) {
        List<String> reconstructedBefore = new ArrayList<>();
        List<String> reconstructedAfter = new ArrayList<>();
        edits.forEach(edit -> {
            if (edit.operation() != MyersDiff.Operation.INSERT) {
                reconstructedBefore.add(edit.value());
            }
            if (edit.operation() != MyersDiff.Operation.DELETE) {
                reconstructedAfter.add(edit.value());
            }
        });
        assertThat(reconstructedBefore).isEqualTo(before);
        assertThat(reconstructedAfter).isEqualTo(after);
    }

    private List<String> randomSequence(Random random) {
        int size = random.nextInt(9);
        List<String> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            values.add("item-" + random.nextInt(5));
        }
        return List.copyOf(values);
    }

    private int longestCommonSubsequence(List<String> before, List<String> after) {
        int[][] lengths = new int[before.size() + 1][after.size() + 1];
        for (int left = 1; left <= before.size(); left++) {
            for (int right = 1; right <= after.size(); right++) {
                lengths[left][right] = before.get(left - 1).equals(after.get(right - 1))
                        ? lengths[left - 1][right - 1] + 1
                        : Math.max(lengths[left - 1][right], lengths[left][right - 1]);
            }
        }
        return lengths[before.size()][after.size()];
    }
}
