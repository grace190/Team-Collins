package org.broadinstitute.hellbender.tools.spark.sv.discovery.alignment;

import com.google.common.annotations.VisibleForTesting;
import htsjdk.samtools.Cigar;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.TextCigarCodec;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Tail;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.read.CigarBuilder;
import org.broadinstitute.hellbender.utils.read.CigarUtils;
import scala.Tuple2;
import scala.Tuple3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ContigAlignmentsModifier {

    /**
     * Given {@code clipLengthOnRead} to be clipped from an aligned contig's {@link AlignmentInterval} {@code input},
     * return a modified {@link AlignmentInterval} with the requested number of bases clipped away and ref span recomputed.
     * <p>
     *     Note that the returned AlignmentInterval will have its {@code NM} set to {@link AlignmentInterval#NO_NM}
     *     whereas its {@code AS} and mapping quality are simply copied from the input alignment
     *     (recalculating them doesn't payoff at this stage, and makes the alignment filtering step more complicated since they are used there)
     * </p>
     * @param input                 alignment to be modified (record not modified in place)
     * @param clipLengthOnRead      number of read bases to be clipped away
     * @param clipFrom3PrimeEnd     to clip from the 3' end of the read or not
     */
    public static AlignmentInterval clipAlignmentInterval(final AlignmentInterval input, final int clipLengthOnRead,
                                                          final boolean clipFrom3PrimeEnd) {
        Utils.nonNull(input);
        if (clipLengthOnRead < 0) {
            throw new IllegalArgumentException("requesting negative clip length: " + clipLengthOnRead + " on " + input.toPackedString());
        }
        Utils.validateArg(clipLengthOnRead < input.endInAssembledContig - input.startInAssembledContig + 1,
                            "input alignment to be clipped away: " + input.toPackedString() + "\twith clip length: " + clipLengthOnRead);

        final Tuple2<SimpleInterval, Cigar> newRefSpanAndCigar = computeNewRefSpanAndCigar(input, clipLengthOnRead, clipFrom3PrimeEnd);
        final Tuple2<Integer, Integer> newContigStartAndEnd =
                computeNewReadSpan(input.startInAssembledContig, input.endInAssembledContig, newRefSpanAndCigar._2,
                        clipLengthOnRead, clipFrom3PrimeEnd);
        return new AlignmentInterval(newRefSpanAndCigar._1, newContigStartAndEnd._1, newContigStartAndEnd._2, newRefSpanAndCigar._2,
                input.forwardStrand, input.mapQual, AlignmentInterval.NO_NM, AlignmentInterval.NO_AS, AlnModType.UNDERGONE_OVERLAP_REMOVAL);
    }

    /**
     * The new read span can NOT be simply calculated by subtracting the requested {@code clipLengthOnRead},
     * for a reason that can be demonstrated below:
     * suppose an alignment has cigar "20S100M10I...", and it is being clipped from the 5'-end with a length of 105.
     * If we simply use the 105 to calculate the new start, it would be 21 + 105 = 126,
     * but because the whole 100M alignment block would be clipped away, the new start should be 131.
     */
    private static Tuple2<Integer, Integer> computeNewReadSpan(final int originalContigStart, final int originalContigEnd,
                                                               final Cigar newCigarAlong5to3DirectionOfContig,
                                                               final int clipLengthOnRead, final boolean clipFrom3PrimeEnd) {
        final int newTigStart, newTigEnd;
        if (clipFrom3PrimeEnd) {
            newTigStart = originalContigStart;
            newTigEnd   = Math.min(originalContigEnd - clipLengthOnRead,
                                   CigarUtils.countUnclippedReadBases(newCigarAlong5to3DirectionOfContig) -
                                           CigarUtils.countClippedBases(newCigarAlong5to3DirectionOfContig, Tail.RIGHT));
        } else {
            newTigStart = Math.max(originalContigStart + clipLengthOnRead,
                                   CigarUtils.countClippedBases(newCigarAlong5to3DirectionOfContig, Tail.LEFT) + 1);
            newTigEnd   = originalContigEnd;
        }

        return new Tuple2<>(newTigStart, newTigEnd);
    }

    /**
     * Given {@code clipLengthOnRead} to be clipped from an aligned contig's {@link AlignmentInterval} {@code input},
     * return a modified reference span with the requested number of bases clipped away and new cigar.
     * @param input                 alignment to be modified (record not modified in place)
     * @param clipLengthOnRead      number of read bases to be clipped away
     * @param clipFrom3PrimeEnd     to clip from the 3' end of the read or not
     * @throws IllegalArgumentException if the {@code input} alignment contains {@link CigarOperator#P} or {@link CigarOperator#N} operations
     */
    @VisibleForTesting
    static Tuple2<SimpleInterval, Cigar> computeNewRefSpanAndCigar(final AlignmentInterval input, final int clipLengthOnRead,
                                                                   final boolean clipFrom3PrimeEnd) {
        Utils.validateArg(input.cigarAlong5to3DirectionOfContig.getCigarElements().stream().map(CigarElement::getOperator)
                        .noneMatch(op -> op.equals(CigarOperator.N) || op.isPadding()),
                "Input alignment contains padding or skip operations, which is currently unsupported: " + input.toPackedString());

        final Tuple3<List<CigarElement>, List<CigarElement>, List<CigarElement>> threeSections = splitCigarByLeftAndRightClipping(input);
        final List<CigarElement> leftClippings = threeSections._1();
        final List<CigarElement> unclippedCigarElementsForThisAlignment = threeSections._2();
        final List<CigarElement> rightClippings = threeSections._3();

        int readBasesConsumed = 0, refBasesConsumed = 0;
        final List<CigarElement> cigarElements = new ArrayList<>(unclippedCigarElementsForThisAlignment);
        if (clipFrom3PrimeEnd) Collections.reverse(cigarElements);
        final List<CigarElement> newMiddleSection = new ArrayList<>(unclippedCigarElementsForThisAlignment.size());
        for (int idx = 0; idx < cigarElements.size(); ++idx) {
            final CigarElement ce = cigarElements.get(idx);
            if (ce.getOperator().consumesReadBases()) {
                if (readBasesConsumed + ce.getLength() < clipLengthOnRead) {
                    readBasesConsumed += ce.getLength();
                } else { // enough read bases would be clipped, note that here we can have only either 'M' or 'I'

                    if (!ce.getOperator().isAlignment() && !ce.getOperator().equals(CigarOperator.I))
                        throw new GATKException.ShouldNeverReachHereException("Logic error, should not reach here: operation consumes read bases but is neither alignment nor insertion.\n " +
                                "Original cigar(" + TextCigarCodec.encode(input.cigarAlong5to3DirectionOfContig) + ")\tclipLengthOnRead(" + clipLengthOnRead + ")\t" + (clipFrom3PrimeEnd ? "clipFrom3PrimeEnd" : "clipFrom5PrimeEnd"));

                    // deal with cigar first
                    newMiddleSection.add( new CigarElement(clipLengthOnRead, CigarOperator.S) );
                    // # of bases left, for the current operation, after requested clipping is done,
                    // may be 0 because we probably don't need the entire current operation to have the requested
                    // number of read bases clipped, e.g. we only 15 more bases from the current operation,
                    // but its length is 20, then 5 bases should be "left over"
                    final int leftOverBasesForCurrOp = readBasesConsumed + ce.getLength() - clipLengthOnRead;
                    if (leftOverBasesForCurrOp!=0) {
                        newMiddleSection.add(// again note that here we can have only either 'M' or 'I'
                                new CigarElement(leftOverBasesForCurrOp, ce.getOperator().isAlignment() ? CigarOperator.M
                                                                                                        : CigarOperator.S) );
                    }
                    newMiddleSection.addAll( cigarElements.subList(idx+1, cigarElements.size()) );

                    // then deal with ref span
                    refBasesConsumed += ce.getOperator().isAlignment() ? (clipLengthOnRead - readBasesConsumed)
                                                                       : 0;

                    break;
                }
            }
            if ( ce.getOperator().consumesReferenceBases() ) { // if reaches here, not enough read bases have been consumed
                refBasesConsumed += ce.getLength();
            }
        }

        final String messageWhenErred = input.toPackedString() + "\tclip length: " + clipLengthOnRead + "\tclip from end: " + (clipFrom3PrimeEnd? "3":"5") + " of read";
        if ( newMiddleSection.size() < 2 ){
            throw new GATKException("The input alignment after clipping contains no or only one operation. " +
                    "This indicates a logic error or too large a clipping length (whole alignment being clipped away) or invalid input cigar.\n" +
                    messageWhenErred);
        }

        // guard against edge case where the requested clipping removes a whole alignment block that neighbors an indel operation
        // e.g. "30M5I..." and user requests 30 bases to be chipped away hence leading to "...S5I", which we consider to be invalid,
        // so we pack the "30S5I" into a single "35S", or for another example, "25M3D...", with 25 bases to be clipped, we turn it to "25S"
        // for both cases we take of the the correct reference span too
        final CigarElement firstOp = newMiddleSection.get(0);
        final CigarElement secondOp = newMiddleSection.get(1);
        if ( firstOp.getOperator().equals(CigarOperator.S) && secondOp.getOperator().isIndel() ) {
            if (newMiddleSection.size() < 3) {
                throw new GATKException(
                        "After clipping, the new cigar would contain NO aligned bases (i.e. either clipped or gap or mix), " +
                        "This indicates a logic error or too large a clipping length (whole alignment being clipped away) or invalid input cigar.\n" +
                        messageWhenErred);
            }

            refBasesConsumed += secondOp.getOperator().consumesReferenceBases() ? secondOp.getLength() : 0;
            if (secondOp.getOperator().equals(CigarOperator.D)) {
                newMiddleSection.remove( 1 );
            } else {
                // remove first cigar operation, THEN update the new head with the total clipping
                newMiddleSection.remove( 0);
                newMiddleSection.set(0,
                        new CigarElement(firstOp.getLength() + secondOp.getLength(), CigarOperator.S));
            }
        }

        if (clipFrom3PrimeEnd) Collections.reverse(newMiddleSection);
        final Cigar newCigar = new CigarBuilder().addAll(leftClippings).addAll(newMiddleSection).addAll(rightClippings).make();

        final SimpleInterval newRefSpan;
        if (clipFrom3PrimeEnd == input.forwardStrand) {
            newRefSpan = new SimpleInterval(input.referenceSpan.getContig(),
                    input.referenceSpan.getStart(),
                    input.referenceSpan.getEnd() - refBasesConsumed );
        } else {
            newRefSpan = new SimpleInterval(input.referenceSpan.getContig(),
                    input.referenceSpan.getStart() + refBasesConsumed ,
                    input.referenceSpan.getEnd());
        }

        return new Tuple2<>(newRefSpan, newCigar);
    }

    /**
     * Extract, from provided {@code input} alignment, three parts of cigar elements:
     * <ul>
     *     <li> a list of cigar elements leading to the start position of the input alignment on the read</li>
     *     <li> </li>
     *     <li> a list of cigar elements after the end position of the input alignment on the read</li>
     * </ul>
     * For example, an alignment with cigar "10H20S5M15I25M35D45M30S40H" with start pos 31 and end pos 120,
     * the result would be ([10H, 20S], [5M, 15I, 25M, 35D, 45M], [30S, 40H]).
     */
    @VisibleForTesting
    static Tuple3<List<CigarElement>, List<CigarElement>, List<CigarElement>> splitCigarByLeftAndRightClipping(final AlignmentInterval input) {
        final List<CigarElement> cigarElements = input.cigarAlong5to3DirectionOfContig.getCigarElements();
        final List<CigarElement> left = new ArrayList<>(cigarElements.size()),
                                 middle = new ArrayList<>(cigarElements.size()),
                                 right = new ArrayList<>(cigarElements.size());
        // using input's startInAssembledContig and endInAssembledContig in testing conditions because they must be the boundaries of alignment operations
        int readBasesConsumed = 0;
        for(final CigarElement ce : cigarElements) {
            if (readBasesConsumed < input.startInAssembledContig-1) {
                left.add(ce);
            } else if (readBasesConsumed < input.endInAssembledContig) {
                middle.add(ce);
            } else {
                right.add(ce);
            }
            readBasesConsumed += ce.getOperator().consumesReadBases() || ce.getOperator().equals(CigarOperator.H)? ce.getLength() : 0;
        }

        if (middle.isEmpty())
            throw new GATKException("Logic error: cigar elements corresponding to alignment block is empty. " + input.toPackedString());

        return new Tuple3<>(left, middle, right);
    }

    public enum AlnModType {
        NONE, UNDERGONE_OVERLAP_REMOVAL, EXTRACTED_FROM_LARGER_ALIGNMENT, FROM_SPLIT_GAPPED_ALIGNMENT;

        public enum ModTypeString {
            O, H, E, S
        }
        @Override
        public String toString() {
            switch (this) {
                case NONE: return ModTypeString.O.name();
                case UNDERGONE_OVERLAP_REMOVAL: return ModTypeString.H.name();
                case EXTRACTED_FROM_LARGER_ALIGNMENT: return ModTypeString.E.name();
                case FROM_SPLIT_GAPPED_ALIGNMENT: return ModTypeString.S.name();
                default: throw new IllegalArgumentException();
            }
        }
    }

    private static class GapSplitter {
        private static final int NOT_SET = -1;

        // boundaries of each ungapped alignment chunk
        int alignmentStartContig = NOT_SET; // contig starting position
        int alignmentStartIdx = NOT_SET; // cigar elements starting position
        int alignmentStartRef = NOT_SET; // reference starting position
        int alignmentEndContig = NOT_SET; // contig ending position
        int alignmentEndIdx = NOT_SET; // cigar elements ending position
        int alignmentEndRef = NOT_SET; // reference ending position

        final AlignmentInterval oneRegion;
        final int unclippedContigLen;
        final List<CigarElement> cigarElements;

        public GapSplitter( final AlignmentInterval oneRegion, final int unclippedContigLen ) {
            this.oneRegion = oneRegion;
            this.unclippedContigLen = unclippedContigLen;
            cigarElements = oneRegion.cigarAlong5to3DirectionOfContig.getCigarElements();
        }

        public List<AlignmentInterval> splitGaps( final int sensitivity ) {
            int nElements = cigarElements.size();
            if ( nElements <= 1 ) {
                return new ArrayList<>( Collections.singletonList(oneRegion) );
            }

            int contigOffset = 0;
            int elementIdx = 0;
            if ( cigarElements.get(0).getOperator() == CigarOperator.H ) {
                contigOffset += cigarElements.get(0).getLength();
                elementIdx += 1;
            }

            if ( cigarElements.get(nElements - 1).getOperator() == CigarOperator.H ) {
                nElements -= 1;
            }

            int refOffset = oneRegion.referenceSpan.getStart();
            if ( !oneRegion.forwardStrand ) refOffset = -oneRegion.referenceSpan.getEnd();

            final List<AlignmentInterval> result = new ArrayList<>();
            while ( elementIdx < nElements ) {
                final CigarElement element = cigarElements.get(elementIdx);
                final CigarOperator op = element.getOperator();
                final int len = element.getLength();
                if ( op.isAlignment() ) {
                    if ( alignmentStartContig == NOT_SET ) {
                        alignmentStartContig = contigOffset;
                        alignmentStartIdx = elementIdx;
                        alignmentStartRef = refOffset;
                    }
                    alignmentEndContig = contigOffset + len;
                    alignmentEndIdx = elementIdx + 1;
                    alignmentEndRef = refOffset + len;
                } else if ( op.isIndel() && len >= sensitivity && alignmentStartContig != NOT_SET ) {
                    result.add(grabCurrentInterval());
                    alignmentStartContig = NOT_SET;
                }
                if ( op.consumesReadBases() ) {
                    contigOffset += len;
                }
                if ( op.consumesReferenceBases() ) {
                    refOffset += len;
                }
                elementIdx += 1;
            }

            if ( alignmentStartContig != NOT_SET ) {
                result.add(grabCurrentInterval());
            }

            if ( result.size() < 2 ) {
                return new ArrayList<>(Collections.singletonList(oneRegion));
            }
            return result;
        }

        private AlignmentInterval grabCurrentInterval() {
            final List<CigarElement> alignmentElements = new ArrayList<>();
            int initialSoftClipLen = alignmentStartContig;
            CigarElement initialHardClip = cigarElements.get(0);
            if ( initialHardClip.getOperator() == CigarOperator.H ) {
                alignmentElements.add(initialHardClip);
                initialSoftClipLen -= initialHardClip.getLength();
            }
            if ( initialSoftClipLen > 0 ) {
                alignmentElements.add(new CigarElement(initialSoftClipLen, CigarOperator.S));
            }
            alignmentElements.addAll(cigarElements.subList(alignmentStartIdx, alignmentEndIdx));
            int finalSoftClipLen = unclippedContigLen - alignmentEndContig;
            CigarElement finalHardClip = cigarElements.get(cigarElements.size() - 1);
            if ( finalHardClip.getOperator() == CigarOperator.H ) {
                finalSoftClipLen -= finalHardClip.getLength();
                if ( finalSoftClipLen > 0 ) {
                    alignmentElements.add(new CigarElement(finalSoftClipLen, CigarOperator.S));
                }
                alignmentElements.add(finalHardClip);
            } else if ( finalSoftClipLen > 0 ) {
                alignmentElements.add(new CigarElement(finalSoftClipLen, CigarOperator.S));
            }
            final String refContig = oneRegion.referenceSpan.getContig();
            final SimpleInterval refSpan;
            if ( oneRegion.forwardStrand ) {
                refSpan = new SimpleInterval(refContig, alignmentStartRef, alignmentEndRef - 1);
            } else {
                refSpan = new SimpleInterval(refContig, -alignmentEndRef + 1, -alignmentStartRef);
            }
            return new AlignmentInterval(
                    refSpan, alignmentStartContig + 1, alignmentEndContig,
                    new Cigar(alignmentElements), oneRegion.forwardStrand, oneRegion.mapQual,
                    AlignmentInterval.NO_NM, AlignmentInterval.NO_AS,
                    ContigAlignmentsModifier.AlnModType.FROM_SPLIT_GAPPED_ALIGNMENT);
        }
    }

    /**
     * Splits a gapped alignment into multiple alignment regions, based on input sensitivity: i.e. if the gap(s) in the input alignment
     * is equal to or larger than the size specified by {@code sensitivity}, two alignment regions will be generated.
     *
     * To fulfill this functionality, needs to accomplish three key tasks correctly:
     * <ul>
     *     <li>generate appropriate cigar,</li>
     *     <li>infer reference coordinates,</li>
     *     <li>infer contig coordinates</li>
     * </ul>
     * As an example, an alignment with CIGAR "397S118M2D26M6I50M7I26M1I8M13D72M398S", when {@code sensitivity} is set to "1", should be split into 5 alignments
     * <ul>
     *     <li>"397S118M594S"</li>
     *     <li>"515S26M568S"</li>
     *     <li>"547S50M512S"</li>
     *     <li>"631S8M470S"</li>
     *     <li>"639S72M398S"</li>
     * </ul>
     * On the other hand, an alignment with CIGAR "10M10D10M60I10M10I10M50D10M", when {@code sensitivity} is set to "50", should be split into 3 alignments
     * <ul>
     *     <li>"10M10D10M100S"</li>
     *     <li>"80S10M10I10M10S"</li>
     *     <li>"110S10M"</li>
     * </ul>
     * And when an alignment has hard clipping adjacent to soft clippings, e.g. "1H2S3M5I10M20D6M7S8H", it should be split into alignments with CIGAR resembling the original CIGAR as much as possible:
     * <ul>
     *     <li>"1H2S3M28S8H"</li>
     *     <li>"1H10S10M13S8H"</li>
     *     <li>"1H20S6M7S8H"</li>
     * </ul>
     *
     * @return an iterable of size >= 1. if size==1, the returned iterable contains only the input (i.e. either no gap or hasn't reached sensitivity)
     */
    @VisibleForTesting
    public static Iterable<AlignmentInterval> splitGappedAlignment( final AlignmentInterval oneRegion,
                                                                    final int sensitivity,
                                                                    final int unclippedContigLen ) {
        return new GapSplitter(oneRegion, unclippedContigLen).splitGaps(sensitivity);
    }
}
