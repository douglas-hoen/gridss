package au.edu.wehi.idsv;

import htsjdk.samtools.SAMRecord;
import au.edu.wehi.idsv.sam.SAMRecordUtil;

public class DiscordantReadPair extends NonReferenceReadPair implements DirectedBreakpoint {
	protected DiscordantReadPair(SAMRecord local, SAMRecord remote, SAMEvidenceSource source) {
		super(local, remote, source);
		assert(!remote.getReadUnmappedFlag());
	}
	@Override
	public BreakpointSummary getBreakendSummary() {
		return (BreakpointSummary)super.getBreakendSummary();
	}
	@Override
	public int getRemoteMapq() {
		return getNonReferenceRead().getMappingQuality();
	}
	@Override
	public int getRemoteBaseLength() {
		return getNonReferenceRead().getReadLength();
	}

	@Override
	public int getRemoteBaseCount() {
		return getNonReferenceRead().getReadLength();
	}

	@Override
	public int getRemoteMaxBaseQual() {
		return SAMRecordUtil.getMaxReferenceBaseQual(getNonReferenceRead());
	}

	@Override
	public int getRemoteTotalBaseQual() {
		return SAMRecordUtil.getTotalReferenceBaseQual(getNonReferenceRead());
	}
	@Override
	public boolean fragmentSequencesOverlap() {
		return getLocalledMappedRead().getReferenceIndex() == getNonReferenceRead().getReferenceIndex()
			&& ((getLocalledMappedRead().getAlignmentStart() >= getNonReferenceRead().getAlignmentStart() && getLocalledMappedRead().getAlignmentStart() <= getNonReferenceRead().getAlignmentEnd()) ||
				(getNonReferenceRead().getAlignmentStart() >= getLocalledMappedRead().getAlignmentStart() && getNonReferenceRead().getAlignmentStart() <= getLocalledMappedRead().getAlignmentEnd()));
	}
}
