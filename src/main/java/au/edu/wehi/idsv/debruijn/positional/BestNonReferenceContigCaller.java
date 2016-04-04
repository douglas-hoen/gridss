package au.edu.wehi.idsv.debruijn.positional;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;


/**
 * Calls optimal contigs from a positional de Bruijn graph
 * 
 * @author Daniel Cameron
 *
 */
public class BestNonReferenceContigCaller extends ContigCaller {
	private final MemoizedTraverse frontier = new MemoizedTraverse();
	/**
	 * Potential starting nodes.
	 * We need to wait until all previous nodes are defined before checking
	 * for starting node intervals.
	 */
	private final PriorityQueue<KmerPathNode> unprocessedStartNodes = new PriorityQueue<KmerPathNode>(KmerNodeUtil.ByLastEnd);
	/**
	 * Assembled contigs. The best contig is called once all potential alternate contigs
	 * involving the evidence used to construct the contig have also been assembled.
	 */
	private final PriorityQueue<Contig> called = new PriorityQueue<Contig>(Contig.ByScoreDescPosition);
	private long consumed = 0;
	private int firstContigPosition = Integer.MAX_VALUE;
	private int inputPosition;
	public BestNonReferenceContigCaller(
			Iterator<KmerPathNode> it,
			int maxEvidenceWidth) {
		super(it, maxEvidenceWidth);
	}
	private void advance() {
		inputPosition = nextPosition();
		advanceUnderlying();
		advanceUnprocessed();
		advanceFrontier();
	}
	/**
	 * Loads records from the underlying stream up to and including the current inputPosition.
	 */
	private void advanceUnderlying() {
		while (underlying.hasNext() && underlying.peek().firstStart() <= inputPosition) {			
			KmerPathNode nextRecord = underlying.next();
			consumed++;
			queueForProcessing(nextRecord);
		}
	}
	private void advanceUnprocessed() {
		// final kmer ending before inputPosition means that all adjacent nodes have been loaded
		while (!unprocessedStartNodes.isEmpty() && unprocessedStartNodes.peek().lastEnd() < inputPosition) {
			KmerPathNode unprocessed = unprocessedStartNodes.poll();
			addStartingPaths(unprocessed);
		}
	}
	private void advanceFrontier() {
		for (TraversalNode head = frontier.peekFrontier(); head != null && head.node.lastEnd() < inputPosition; head = frontier.peekFrontier()) {
			visit(frontier.pollFrontier());
		}
	}
	private void queueForProcessing(KmerPathNode node) {
		if (!node.isReference()) {
			unprocessedStartNodes.add(node);
		}
	}
	/**
	 * Adds starting paths for the given node to the graph
	 * 
	 * A starting path is a position interval in which either
	 * a) no predecessors exist, or
	 * b) at least one reference kmer predecessor exists
	 * 
	 * @param node
	 */
	private void addStartingPaths(KmerPathNode node) {
		assert(!node.isReference());
		PeekingIterator<KmerPathNode> startIt = Iterators.peekingIterator(node.prev().iterator());
		int start = node.firstStart();
		final int scopeEnd = node.firstEnd();
		int nonReferenceCount = 0;
		int referenceCount = 0;
		// TODO: is using a linear array faster?
		PriorityQueue<KmerPathNode> active = new PriorityQueue<KmerPathNode>(3, KmerNodeUtil.ByLastEnd);
		while (start <= scopeEnd) {
			// advance
			while (startIt.hasNext() && startIt.peek().lastStart() < start) {
				KmerPathNode n = startIt.next();
				if (n.isReference()) referenceCount++;
				else nonReferenceCount++;
				active.add(n);
			}
			while (!active.isEmpty() && active.peek().lastEnd() + 1 < start) {
				KmerPathNode n = active.poll();
				if (n.isReference()) referenceCount--;
				else nonReferenceCount--;
			}
			int end = scopeEnd;
			if (startIt.hasNext()) {
				end = Math.min(end, startIt.peek().lastStart());
			}
			if (!active.isEmpty()) {
				end = Math.min(end, active.peek().lastEnd() + 1);
			}
			if (referenceCount > 0) {
				// start of anchored path
				frontier.memoize(new TraversalNode(new KmerPathSubnode(node, start, end), ANCHORED_SCORE));
			} else if (referenceCount == 0 && nonReferenceCount == 0) {
				// start of unanchored path
				frontier.memoize(new TraversalNode(new KmerPathSubnode(node, start, end), 0));
			}
			start = end + 1;
		}
	}
	private void visit(TraversalNode ms) {
		assert(ms.node.lastEnd() < inputPosition); // successors must be fully defined
		RangeSet<Integer> terminalAnchor = null;
		for (KmerPathSubnode sn : ms.node.next()) {
			if (!sn.node().isReference()) {
				frontier.memoize(new TraversalNode(ms, sn));
			} else {
				if (terminalAnchor == null) {
					terminalAnchor = TreeRangeSet.create();
				}
				terminalAnchor.add(Range.closed(ms.node.firstStart(), ms.node.firstEnd()));
			}
		}
		if (terminalAnchor != null) {
			// path has reference successor = path is anchored to the reference here
			for (Range<Integer> rs : terminalAnchor.asRanges()) {
				callContig(new Contig(new TraversalNode(ms, rs.lowerEndpoint(), rs.upperEndpoint()), true));
			}	
		}
		for (Range<Integer> rs : ms.node.nextPathRangesOfDegree(KmerPathSubnode.NO_EDGES).asRanges()) {
			// path has no successors = end of path
			callContig(new Contig(new TraversalNode(ms, rs.lowerEndpoint(), rs.upperEndpoint()), false));
		}
	}
	private void callContig(Contig contig) {
		firstContigPosition = Math.min(contig.node.node.firstStart() - (contig.node.pathLength - 1), firstContigPosition);
		called.add(contig);
	}
	/**
	 * Determines whether the contig could overlap evidence with another contig
	 * that we have not yet generated. We need to ensure that the contig is
	 * at least maxEvidenceWidth away from:
	 * a) all partially generated contig
	 * b) all unprocessed contig start locations
	 * c) any additional nodes not yet read from the underlying stream
	 *  
	 * @param contig
	 * @return true if the contig will not share evidence with future contigs, false otherwise
	 */
	private boolean contigDoesNotShareEvidenceWithUnprocessed(Contig contig) {
		assert(contig != null);
		int contigLastEnd = contig.node.node.lastEnd();
		int frontierFirstStart = frontier.isEmptyFrontier() ? Integer.MAX_VALUE : frontier.peekFrontier().node.firstStart();
		int unprocessedFirstNodeLastEnd = unprocessedStartNodes.isEmpty() ? Integer.MAX_VALUE : unprocessedStartNodes.peek().lastEnd();
		int unprocessedFirstStart = unprocessedFirstNodeLastEnd - maxEvidenceWidth; // node could contain entire evidence
		int firstStart = Math.min(frontierFirstStart, Math.min(unprocessedFirstStart, inputPosition));
		return contigLastEnd < firstStart - maxEvidenceWidth; // evidence could overlap just contig last end
	}
	@Override
	public ArrayDeque<KmerPathSubnode> bestContig() {
		// FIXME: add hard safety bounds to the width of the loaded graph
		// since the size is technically unbounded
		while (underlying.hasNext() && (called.isEmpty() || !contigDoesNotShareEvidenceWithUnprocessed(called.peek()))) {
			advance();
		}
		if (!underlying.hasNext()) {
			// final advance to end of input
			advance();
			assert(frontier.peekFrontier() == null);
		}
		Contig best = called.peek();
		if (best == null) {
			assert(!underlying.hasNext());
			return null;
		}
		//try {
		//	frontier.export(new File("C:/temp/dump.csv"));
		//} catch (IOException e) {
		//	e.printStackTrace();
		//}
		return best.toSubnodePath();
	}
	public List<ArrayDeque<KmerPathSubnode>> contigsFound() {
		bestContig();
		return called.stream().sorted(Contig.ByScoreDescPosition).map(c -> c.toSubnodePath()).collect(Collectors.toList());
	}
	public int tracking_contigCount() {
		return called.size();
	}
	public int tracking_contigFirstPosition() {
		return firstContigPosition;
	}
	public long tracking_underlyingConsumed() {
		return consumed;
	}
	public int tracking_memoizedNodeCount() {
		return frontier.tracking_memoizedNodeCount();
	}
	public int tracking_frontierSize() {
		return frontier.tracking_frontierSize();
	}
	public int tracking_unprocessedStartNodeCount() {
		return unprocessedStartNodes.size();
	}
}
