package org.broadinstitute.hellbender.tools.walkers.annotator;

import com.google.common.primitives.Doubles;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.GenotypesContext;
import htsjdk.variant.variantcontext.VariantContext;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.utils.MannWhitneyU;
import org.broadinstitute.hellbender.utils.QualityUtils;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.genotyper.AlleleLikelihoods;
import org.broadinstitute.hellbender.utils.read.GATKRead;

import java.util.*;


/**
 * Abstract root for all RankSum based annotations
 */
public abstract class RankSumTest implements InfoFieldAnnotation, Annotation {
    protected final static double INVALID_ELEMENT_FROM_READ = Double.NEGATIVE_INFINITY;

    @Override
    public Map<String, Object> annotate(final ReferenceContext ref,
                                        final VariantContext vc,
                                        final AlleleLikelihoods<GATKRead, Allele> likelihoods) {
        Utils.nonNull(vc, "vc is null");

        final GenotypesContext genotypes = vc.getGenotypes();
        if (genotypes == null || genotypes.isEmpty()) {
            return Collections.emptyMap();
        }

        final List<Double> refQuals = new ArrayList<>();
        final List<Double> altQuals = new ArrayList<>();

        if( likelihoods != null) {
            fillQualsFromLikelihood(vc, likelihoods, refQuals, altQuals);
        }


        if ( refQuals.isEmpty() && altQuals.isEmpty() ) {
            return Collections.emptyMap();
        }

        final MannWhitneyU mannWhitneyU = new MannWhitneyU();

        // we are testing that set1 (the alt bases) have lower quality scores than set2 (the ref bases)
        final MannWhitneyU.Result result = mannWhitneyU.test(Doubles.toArray(altQuals), Doubles.toArray(refQuals), MannWhitneyU.TestType.FIRST_DOMINATES);
        final double zScore = result.getZ();

        if (Double.isNaN(zScore)) {
            return Collections.emptyMap();
        } else {
            return Collections.singletonMap(getKeyNames().get(0), String.format("%.3f", zScore));
        }
    }

    protected void fillQualsFromLikelihood(VariantContext vc, AlleleLikelihoods<GATKRead, Allele> likelihoods, List<Double> refQuals, List<Double> altQuals) {
        for (final AlleleLikelihoods<GATKRead, Allele>.BestAllele bestAllele : likelihoods.bestAllelesBreakingTies()) {
            final GATKRead read = bestAllele.evidence;
            final Allele allele = bestAllele.allele;
            if (bestAllele.isInformative() && isUsableRead(read, vc)) {
                final OptionalDouble value = getElementForRead(read, vc, bestAllele);
                // Bypass read if the clipping goal is not reached or the refloc is inside a spanning deletion
                if (value.isPresent() && value.getAsDouble() != INVALID_ELEMENT_FROM_READ) {
                    if (allele.isReference()) {
                        refQuals.add(value.getAsDouble());
                    } else if (vc.hasAllele(allele)) {
                        altQuals.add(value.getAsDouble());
                    }
                }
            }
        }
    }

    /**
     * Get the element for the given read at the given reference position
     *
     * @param read     the read
     * @param vc       the variant to be annotated
     * @param bestAllele the most likely allele for this read
     * @return a Double representing the element to be used in the rank sum test, or null if it should not be used
     */
    protected OptionalDouble getElementForRead(final GATKRead read, final VariantContext vc, final AlleleLikelihoods<GATKRead, Allele>.BestAllele bestAllele) {
        return getElementForRead(read, vc);
    }

    /**
     * Get the element for the given read at the given reference position
     *
     * @param read     the read
     * @param vc       the variant to be annotated
     * @return an OptionalDouble representing the element to be used in the rank sum test, empty if it should not be used
     */
    protected abstract OptionalDouble getElementForRead(final GATKRead read, final VariantContext vc);

    /**
     * Can the read be used in comparative tests between ref / alt bases?
     *
     * @param read   the read to consider
     * @param vc    the variant to be annotated
     * @return true if this read is meaningful for comparison, false otherwise
     */
    protected boolean isUsableRead(final GATKRead read, final VariantContext vc) {
        Utils.nonNull(read);
        return read.getMappingQuality() != 0 && read.getMappingQuality() != QualityUtils.MAPPING_QUALITY_UNAVAILABLE;
    }

}
