package org.broadinstitute.hellbender.tools.walkers.annotator;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextUtils;
import htsjdk.variant.vcf.VCFConstants;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import htsjdk.variant.vcf.VCFStandardHeaderLines;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.genotyper.AlleleLikelihoods;
import org.broadinstitute.hellbender.utils.help.HelpConstants;
import org.broadinstitute.hellbender.utils.read.GATKRead;

import java.util.*;


/**
 * Counts and frequency of alleles in called genotypes
 *
 * <p>This annotation outputs the following:</p>
 *
 *     <ul>
 *     <li>Number of times each ALT allele is represented, in the same order as listed (AC)</li>
 *     <li>Frequency of each ALT allele, in the same order as listed (AF)</li>
 *     <li>Total number of alleles in called genotypes (AN)</li>
 * </ul>
 * <h3>Example</h3>
 * <pre>AC=1;AF=0.500;AN=2</pre>
 * <p>This set of annotations, relating to a heterozygous call(0/1) means there is 1 alternate allele in the genotype. The corresponding allele frequency is 0.5 because there is 1 alternate allele and 1 reference allele in the genotype.
 * The total number of alleles in the genotype should be equivalent to the ploidy of the sample.</p>
 *
 */
@DocumentedFeature(groupName=HelpConstants.DOC_CAT_ANNOTATORS, groupSummary=HelpConstants.DOC_CAT_ANNOTATORS_SUMMARY, summary="Counts and frequency of alleles in called genotypes (AC, AF, AN)")
public final class ChromosomeCounts implements InfoFieldAnnotation, StandardAnnotation {

    public static final String[] keyNames = {
            VCFConstants.ALLELE_NUMBER_KEY,
            VCFConstants.ALLELE_COUNT_KEY,
            VCFConstants.ALLELE_FREQUENCY_KEY };

    @Override
    public Map<String, Object> annotate(final ReferenceContext ref,
                                        final VariantContext vc,
                                        AlleleLikelihoods<GATKRead, Allele> likelihoods) {
        Utils.nonNull(vc);
        if ( ! vc.hasGenotypes() ) {
            return Collections.emptyMap();
        }

        return VariantContextUtils.calculateChromosomeCounts(vc, new LinkedHashMap<>(), true, Collections.emptySet());
    }

    @Override
    public List<String> getKeyNames() {
        return Arrays.asList(keyNames);
    }
}
