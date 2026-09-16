package com.cofco.qiqihar.graintrade.designsample.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.*;
import org.junit.jupiter.api.Test;

class ExactVillageCoverageSolverTest {
    private final ExactVillageCoverageSolver solver=new ExactVillageCoverageSolver();

    @Test void coversEveryVillageAndAlwaysSelectsAtLeastThree() {
        var adjacency=graph("001-002","002-003","003-004","004-005");
        var result=solver.solve(adjacency,Set.of());
        assertThat(result.selected()).hasSize(3);
        assertThat(adjacency.keySet()).allMatch(village -> result.selected().contains(village)
                || adjacency.get(village).stream().anyMatch(result.selected()::contains));
    }

    @Test void minimizesCountThenMaximizesExistingPreservationThenUsesCodeOrder() {
        var adjacency=graph("001-002","002-003","003-004","004-005","005-006");
        var result=solver.solve(adjacency,Set.of("002","005","006"));
        assertThat(result.selected()).hasSize(3).contains("002","005");
        assertThat(result.preservedExisting()).isEqualTo(3);
        assertThat(solver.solve(adjacency,Set.of()).selected())
                .isEqualTo(new TreeSet<>(solver.solve(adjacency,Set.of()).selected()));
    }

    @Test void isolatedVillageMustSelectItself() {
        var adjacency=graph("001-002");adjacency.put("003",new TreeSet<>());
        assertThat(solver.solve(adjacency,Set.of()).selected()).contains("003");
    }

    @Test void stopsBeforeAnUnboundedExactSearchCanBlockAPlanningRun() {
        var adjacency=graph("001-002","002-003","003-004","004-005");
        assertThatThrownBy(()->solver.solve(adjacency,Set.of(),1))
                .isInstanceOf(ExactVillageCoverageSolver.SearchLimitExceededException.class);
    }

    private static SortedMap<String,SortedSet<String>> graph(String... edges) {
        SortedMap<String,SortedSet<String>> graph=new TreeMap<>();
        for(String edge:edges){String[] pair=edge.split("-");
            graph.computeIfAbsent(pair[0],ignored->new TreeSet<>()).add(pair[1]);
            graph.computeIfAbsent(pair[1],ignored->new TreeSet<>()).add(pair[0]);}
        return graph;
    }
}
