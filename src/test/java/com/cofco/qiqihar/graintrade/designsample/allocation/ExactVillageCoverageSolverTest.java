package com.cofco.qiqihar.graintrade.designsample.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.*;
import org.junit.jupiter.api.Test;

class ExactVillageCoverageSolverTest {
    private final ExactVillageCoverageSolver solver=new ExactVillageCoverageSolver();

    @Test void smallTownshipsUseOnlyThePointsNeededForLocalCoverage() {
        var single=graph();single.put("001",new TreeSet<>());
        assertThat(solver.solve(single,Set.of()).selected()).containsExactly("001");
        assertThat(solver.solve(graph("001-002"),Set.of()).selected()).containsExactly("001");
        var disconnected=graph();disconnected.put("001",new TreeSet<>());disconnected.put("002",new TreeSet<>());
        assertThat(solver.solve(disconnected,Set.of()).selected()).containsExactly("001","002");
        assertThat(solver.solve(graph("001-002","002-003"),Set.of()).selected()).containsExactly("002");
        disconnected.put("003",new TreeSet<>());
        assertThat(solver.solve(disconnected,Set.of()).selected()).containsExactly("001","002","003");
        assertThatThrownBy(()->solver.solve(graph(),Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void coversEveryVillageAndAlwaysSelectsAtLeastThree() {
        var adjacency=graph("001-002","002-003","003-004","004-005");
        var result=solver.solve(adjacency,Set.of());
        assertThat(result.selected()).hasSize(3);
        assertThat(adjacency.keySet()).allMatch(village -> result.selected().contains(village)
                || adjacency.get(village).stream().anyMatch(result.selected()::contains));
    }

    @Test void validatesAndClampsConfiguredMinimumWithoutAcceptingForeignNeighbors() {
        assertThat(solver.solve(graph("001-002"),Set.of(),1000,3,3).selected()).hasSize(2);
        assertThat(solver.solve(graph("001-002","002-003"),Set.of(),1000,2,3).selected()).hasSize(2);
        assertThat(solver.solve(graph("001-002","002-003","003-004"),Set.of(),1000,1,9).selected()).hasSize(4);
        assertThatThrownBy(()->solver.solve(graph("001-002"),Set.of(),1000,0,3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->solver.solve(graph("001-002"),Set.of(),1000,1,2)).isInstanceOf(IllegalArgumentException.class);
        var foreign=graph("001-002");foreign.get("001").add("OTHER_TOWN");
        assertThatThrownBy(()->solver.solve(foreign,Set.of())).isInstanceOf(IllegalArgumentException.class);
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
