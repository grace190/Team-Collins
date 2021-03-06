package org.broadinstitute.hellbender.tools.walkers.annotator;

import com.google.common.collect.ImmutableList;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFCompoundHeaderLine;
import htsjdk.variant.vcf.VCFFormatHeaderLine;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.utils.genotyper.AlleleLikelihoods;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.variant.GATKVCFHeaderLines;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Annotations relevant to the INFO field of the variant file (ie annotations for sites).
 */
public interface InfoFieldAnnotation extends VariantAnnotation {

    default VCFCompoundHeaderLine.SupportedHeaderLineType annotationType() { return VCFCompoundHeaderLine.SupportedHeaderLineType.INFO; }

    /**
     * Computes the annotation for the given variant and the likelihoods per read.
     * Returns a map from annotation keys to values (may be empty if no annotation is to be added).
     *
     * @param ref Reference context, may be null
     * @param vc Variant to be annotated. Not null.
     * @param likelihoods likelihoods indexed by sample, allele, and read within sample
     */
    public Map<String, Object> annotate(final ReferenceContext ref,
                                                 final VariantContext vc,
                                                 final AlleleLikelihoods<GATKRead, Allele> likelihoods);
}