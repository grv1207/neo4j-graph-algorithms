package org.neo4j.graphalgo.impl;

import com.carrotsearch.hppc.AbstractIterator;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.core.utils.ProgressLogger;
import org.neo4j.graphalgo.impl.AllShortestPaths.Result;
import org.neo4j.graphalgo.impl.msbfs.MultiSourceBFS;
import org.neo4j.graphdb.Direction;

import java.util.Iterator;
import java.util.Spliterators;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * AllShortestPaths:
 * <p>
 * multi-source parallel shortest path between each pair of nodes.
 * <p>
 * Due to the high memory footprint the result set would have we emit each result into
 * a blocking queue. The result stream takes elements from the queue while the workers
 * add elements to it.
 */
public class MSBFSAllShortestPaths extends Algorithm<MSBFSAllShortestPaths> {

    private final Graph graph;
    private final ExecutorService executorService;
    private final BlockingQueue<Result> resultQueue;
    private final int nodeCount;

    public MSBFSAllShortestPaths(Graph graph, ExecutorService executorService) {
        this.graph = graph;
        nodeCount = graph.nodeCount();
        this.executorService = executorService;
        this.resultQueue = new LinkedBlockingQueue<>(); // TODO limit size?
    }

    /**
     * the resultStream(..) method starts the computation and
     * returns a Stream of SP-Tuples (source, target, minDist)
     *
     * @return the result stream
     */
    public Stream<Result> resultStream() {
        executorService.submit(new ShortestPathTask(executorService));
        Iterator<Result> iterator = new AbstractIterator<Result>() {
            @Override
            protected Result fetch() {
                try {
                    Result result = resultQueue.take();
                    if (result.sourceNodeId == -1) {
                        return done();
                    }
                    return result;
                } catch (InterruptedException e1) {
                    throw new RuntimeException(e1);
                }
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                iterator,
                0), false);
    }

    @Override
    public MSBFSAllShortestPaths me() {
        return this;
    }

    /**
     * Dijkstra Task. Takes one element of the counter at a time
     * and starts dijkstra on it. It starts emitting results to the
     * queue once all reachable nodes have been visited.
     */
    private class ShortestPathTask implements Runnable {

        private final ExecutorService executorService;

        private ShortestPathTask(final ExecutorService executorService) {
            this.executorService = executorService;
        }

        @Override
        public void run() {

            final ProgressLogger progressLogger = getProgressLogger();

            new MultiSourceBFS(
                    graph,
                    graph,
                    Direction.OUTGOING,
                    (target, distance, sources) -> {
                        while (sources.hasNext()) {
                            int source = sources.next();
                            final Result result = new Result(
                                    graph.toOriginalNodeId(source),
                                    graph.toOriginalNodeId(target),
                                    distance);

                            try {
                                resultQueue.put(result);
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        progressLogger.logProgress((double) target / (nodeCount - 1));
                    }
            ).run(executorService);

            resultQueue.add(new Result(-1, -1, -1));
        }
    }

}
