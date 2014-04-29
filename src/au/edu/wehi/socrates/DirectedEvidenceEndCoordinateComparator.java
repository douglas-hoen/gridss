package au.edu.wehi.socrates;

import java.util.Comparator;

import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMRecordComparator;
import net.sf.samtools.SAMRecordCoordinateComparator;

/**
 * Orders evidence by ending genomic position
 *
 */
public class DirectedEvidenceEndCoordinateComparator implements Comparator<DirectedEvidence> {
	@Override
	public int compare(DirectedEvidence arg0, DirectedEvidence arg1) {
		BreakpointLocation loc0 = arg0.getBreakpointLocation();
		BreakpointLocation loc1 = arg1.getBreakpointLocation();
		int cmp = ComparatorUtil.integerCompare(loc0.referenceIndex, loc1.referenceIndex);
		if (cmp == 0) cmp = ComparatorUtil.integerCompare(loc0.end, loc1.end);
		return cmp;
	}
}