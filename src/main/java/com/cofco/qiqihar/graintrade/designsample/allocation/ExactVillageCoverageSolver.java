package com.cofco.qiqihar.graintrade.designsample.allocation;

import java.util.*;

/** Exact minimum dominating-set search with the approved deterministic tie breakers. */
public final class ExactVillageCoverageSolver {
    public record Result(SortedSet<String> selected,int preservedExisting) {}

    public Result solve(SortedMap<String,? extends Set<String>> adjacency,Set<String> existing) {
        return solve(adjacency,existing,Long.MAX_VALUE);
    }

    public Result solve(SortedMap<String,? extends Set<String>> adjacency,Set<String> existing,long maxSearchNodes) {
        Objects.requireNonNull(adjacency);Objects.requireNonNull(existing);
        if(maxSearchNodes<1)throw new IllegalArgumentException("Search node limit must be positive");
        if(adjacency.size()<3)throw new IllegalArgumentException("A township must contain at least three villages");
        var codes=new ArrayList<>(adjacency.keySet());
        for(var entry:adjacency.entrySet())for(String neighbor:entry.getValue())
            if(!adjacency.containsKey(neighbor))throw new IllegalArgumentException("Unknown adjacent village "+neighbor);
        int n=codes.size();var index=new HashMap<String,Integer>();
        for(int i=0;i<n;i++)index.put(codes.get(i),i);
        BitSet[] covers=new BitSet[n];
        for(int i=0;i<n;i++){
            covers[i]=new BitSet(n);covers[i].set(i);
            for(String neighbor:adjacency.get(codes.get(i)))covers[i].set(index.get(neighbor));
        }
        Search search=new Search(codes,covers,existing,maxSearchNodes);
        search.seedGreedy();search.visit(new BitSet(n),new BitSet(n));
        BitSet best=search.best;
        SortedSet<String> selected=new TreeSet<>();
        for(int bit=best.nextSetBit(0);bit>=0;bit=best.nextSetBit(bit+1))selected.add(codes.get(bit));
        return new Result(Collections.unmodifiableSortedSet(selected),search.preserved(best));
    }

    private static final class Search {
        final List<String> codes;final BitSet[] covers;final Set<String> existing;final int n;final long maxSearchNodes;
        long visitedNodes;
        final Set<BitSet> visited=new HashSet<>();BitSet best;
        Search(List<String> codes,BitSet[] covers,Set<String> existing,long maxSearchNodes){this.codes=codes;this.covers=covers;this.existing=existing;this.n=codes.size();this.maxSearchNodes=maxSearchNodes;}
        void seedGreedy(){
            BitSet chosen=new BitSet(n),covered=new BitSet(n);
            while(covered.cardinality()<n){int next=-1,gain=-1;for(int i=0;i<n;i++)if(!chosen.get(i)){
                BitSet delta=(BitSet)covers[i].clone();delta.andNot(covered);
                if(delta.cardinality()>gain){gain=delta.cardinality();next=i;}}
                chosen.set(next);covered.or(covers[next]);}
            fillToThree(chosen);best=(BitSet)chosen.clone();
        }
        void visit(BitSet chosen,BitSet covered){
            if(++visitedNodes>maxSearchNodes)throw new SearchLimitExceededException(maxSearchNodes);
            BitSet key=(BitSet)chosen.clone();if(!visited.add(key))return;
            if(covered.cardinality()==n){fillToThree(chosen);consider(chosen);return;}
            if(chosen.cardinality()>=best.cardinality())return;
            int maxGain=0;for(int i=0;i<n;i++)if(!chosen.get(i)){
                BitSet delta=(BitSet)covers[i].clone();delta.andNot(covered);maxGain=Math.max(maxGain,delta.cardinality());}
            int uncovered=n-covered.cardinality();int lower=(uncovered+Math.max(maxGain,1)-1)/Math.max(maxGain,1);
            if(chosen.cardinality()+lower>best.cardinality())return;
            int target=chooseUncovered(covered);
            List<Integer> candidates=new ArrayList<>();
            for(int i=0;i<n;i++)if(!chosen.get(i)&&covers[i].get(target))candidates.add(i);
            candidates.sort(Comparator
                    .<Integer>comparingInt(i->existing.contains(codes.get(i))?0:1)
                    .thenComparing((a,b)->Integer.compare(gain(b,covered),gain(a,covered)))
                    .thenComparing(codes::get));
            for(int candidate:candidates){BitSet nextChosen=(BitSet)chosen.clone();nextChosen.set(candidate);
                BitSet nextCovered=(BitSet)covered.clone();nextCovered.or(covers[candidate]);visit(nextChosen,nextCovered);}
        }
        int chooseUncovered(BitSet covered){
            int bestVertex=-1,bestCandidates=Integer.MAX_VALUE;
            for(int v=covered.nextClearBit(0);v<n;v=covered.nextClearBit(v+1)){
                int count=0;for(BitSet cover:covers)if(cover.get(v))count++;
                if(count<bestCandidates){bestCandidates=count;bestVertex=v;}}
            return bestVertex;
        }
        int gain(int candidate,BitSet covered){BitSet delta=(BitSet)covers[candidate].clone();delta.andNot(covered);return delta.cardinality();}
        void fillToThree(BitSet chosen){
            if(chosen.cardinality()>=3)return;
            List<Integer> remaining=new ArrayList<>();for(int i=0;i<n;i++)if(!chosen.get(i))remaining.add(i);
            remaining.sort(Comparator.<Integer>comparingInt(i->existing.contains(codes.get(i))?0:1).thenComparing(codes::get));
            for(int i:remaining){chosen.set(i);if(chosen.cardinality()==3)return;}
        }
        void consider(BitSet candidate){
            if(candidate.cardinality()<best.cardinality()
                    ||candidate.cardinality()==best.cardinality()&&betterTie(candidate,best))best=(BitSet)candidate.clone();
        }
        boolean betterTie(BitSet left,BitSet right){
            int lp=preserved(left),rp=preserved(right);if(lp!=rp)return lp>rp;
            var l=selectedCodes(left);var r=selectedCodes(right);
            for(int i=0;i<l.size();i++){int comparison=l.get(i).compareTo(r.get(i));if(comparison!=0)return comparison<0;}return false;
        }
        int preserved(BitSet value){int count=0;for(int i=value.nextSetBit(0);i>=0;i=value.nextSetBit(i+1))if(existing.contains(codes.get(i)))count++;return count;}
        List<String> selectedCodes(BitSet value){var result=new ArrayList<String>();for(int i=value.nextSetBit(0);i>=0;i=value.nextSetBit(i+1))result.add(codes.get(i));return result;}
    }

    public static final class SearchLimitExceededException extends RuntimeException {
        public SearchLimitExceededException(long limit){super("Exact coverage search exceeded "+limit+" nodes");}
    }
}
