package org.broadinstitute.hellbender.tools.spark.sv.discovery;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileWriter;
import htsjdk.samtools.SAMFileWriterFactory;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFHeaderLine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.ArgumentCollection;
import org.broadinstitute.barclay.argparser.BetaFeature;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.StructuralVariantDiscoveryProgramGroup;
import org.broadinstitute.hellbender.engine.filters.ReadFilter;
import org.broadinstitute.hellbender.engine.filters.ReadFilterLibrary;
import org.broadinstitute.hellbender.engine.spark.GATKSparkTool;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.spark.sv.StructuralVariationDiscoveryPipelineSpark;
import org.broadinstitute.hellbender.tools.spark.sv.discovery.alignment.AssemblyContigAlignmentsRDDProcessor;
import org.broadinstitute.hellbender.tools.spark.sv.discovery.alignment.AssemblyContigWithFineTunedAlignments;
import org.broadinstitute.hellbender.tools.spark.sv.discovery.inference.CpxVariantInterpreter;
import org.broadinstitute.hellbender.tools.spark.sv.discovery.inference.SegmentedCpxVariantSimpleVariantExtractor;
import org.broadinstitute.hellbender.tools.spark.sv.discovery.inference.SimpleNovelAdjacencyInterpreter;
import org.broadinstitute.hellbender.utils.SVIntervalTree;
import org.broadinstitute.hellbender.tools.spark.sv.utils.SVUtils;
import org.broadinstitute.hellbender.tools.spark.sv.utils.SVVCFWriter;
import org.broadinstitute.hellbender.utils.io.IOUtils;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import scala.Tuple2;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static org.broadinstitute.hellbender.tools.spark.sv.StructuralVariationDiscoveryArgumentCollection.DiscoverVariantsFromContigAlignmentsArgumentCollection;
import static org.broadinstitute.hellbender.tools.spark.sv.discovery.alignment.AssemblyContigWithFineTunedAlignments.AlignmentSignatureBasicType.*;
import static org.broadinstitute.hellbender.tools.spark.sv.discovery.alignment.AssemblyContigWithFineTunedAlignments.ReasonForAlignmentClassificationFailure;

/**
 * (Internal) Examines aligned contigs from local assemblies and calls structural variants or their breakpoints
 *
 * <p>
 *     This is an experimental tool and should not be of interest to most researchers. It is a prototype of a method
 *     for calling structural variation from alignments of assembled contigs and is under active development. For a
 *     more stable method for this, please see DiscoverVariantsFromContigAlignmentsSAMSpark.
 * </p>
 *
 * <p>
 *     This tool takes a file containing the alignments of assembled contigs (typically the output file produced by
 *     FindBreakpointEvidenceSpark) and searches for split alignments or alignments with large gaps indicating the
 *     presence of structural variation breakpoints. The type of each variation is determined by analyzing the
 *     signatures of the split alignments, and are written to VCF files in the designated output directory.
 * </p>
 *
 * <h3>Inputs</h3>
 * <ul>
 *     <li>An input file of assembled contigs aligned to reference.</li>
 *     <li>The reference to which the contigs have been aligned.</li>
 * </ul>
 *
 * <h3>Output</h3>
 * <ul>
 *     <li>Text files describing the discovered structural variants and complex structural variants in the specified output directory.</li>
 * </ul>
 *
 * <h3>Usage example</h3>
 * <pre>
 *   gatk SvDiscoverFromLocalAssemblyContigAlignmentsSpark \
 *     -I assemblies.sam \
 *     -R reference.2bit \
 *     -O output_directory
 * </pre>
 *
 * <h3>Notes</h3>
 * <p>The reference is broadcast by Spark, and must therefore be a .2bit file due to current restrictions.</p>
 */
@DocumentedFeature
@BetaFeature
@CommandLineProgramProperties(
        oneLineSummary = "(Internal) Examines aligned contigs from local assemblies and calls structural variants or their breakpoints",
        summary =
        "This tool is used in development and should not be of interest to most researchers. It is a prototype of" +
        " structural variant calling, and has been under active developments. For more stable version," +
        " please see DiscoverVariantsFromContigAlignmentsSAMSpark." +
        " This tool takes a file containing the alignments of assembled contigs" +
        " (typically the output file produced by FindBreakpointEvidenceSpark) and searches for reads with" +
        " split alignments or large gaps indicating the presence of structural variation breakpoints." +
        " Variations' types are determined by analyzing the signatures of the split alignments," +
        " and are written to VCF files in the designated output directory.",
        programGroup = StructuralVariantDiscoveryProgramGroup.class)
public final class SvDiscoverFromLocalAssemblyContigAlignmentsSpark extends GATKSparkTool {
    private static final long serialVersionUID = 1L;
    private final Logger localLogger = LogManager.getLogger(SvDiscoverFromLocalAssemblyContigAlignmentsSpark.class);

    @ArgumentCollection
    private DiscoverVariantsFromContigAlignmentsArgumentCollection
            discoverStageArgs
            = new DiscoverVariantsFromContigAlignmentsArgumentCollection();

    @Argument(doc = "file containing non-canonical chromosome names (e.g chrUn_KI270588v1) in the reference, human reference (hg19 or hg38) assumed when omitted",
            shortName = "alt-tigs",
            fullName = "non-canonical-contig-names-file", optional = true)
    private String nonCanonicalChromosomeNamesFile;

    @Argument(doc = "prefix for output files (including VCF files and if enabled, the signaling assembly contig's alignments); sample name will be appended after the provided argument",
            shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME)
    private String outputPrefix;

    @Argument(doc = "output query-name sorted SAM files for local assembly contigs whose alignment signature could not be used for emitting un-ambiguous calls",
            fullName = "write-sam", optional = true)
    private boolean writeSAMFiles;

    public static final String SIMPLE_CHIMERA_VCF_FILE_NAME = "NonComplex.vcf";
    public static final String COMPLEX_CHIMERA_VCF_FILE_NAME = "Complex.vcf";
    public static final String REINTERPRETED_1_SEG_CALL_VCF_FILE_NAME = "cpx_reinterpreted_simple_1_seg.vcf";
    public static final String REINTERPRETED_MULTI_SEG_CALL_VCF_FILE_NAME = "cpx_reinterpreted_simple_multi_seg.vcf";
    public static final String MERGED_VCF_FILE_NAME = "merged_simple.vcf";

    @Override
    public boolean requiresReference() {
        return true;
    }

    @Override
    public boolean requiresReads() {
        return true;
    }

    @Override
    public List<ReadFilter> getDefaultReadFilters() {
        return Collections.singletonList(ReadFilterLibrary.MAPPED);
    }

    @Override
    protected void runTool(final JavaSparkContext ctx) {

        validateParams();

        final Broadcast<SVIntervalTree<VariantContext>> cnvCallsBroadcast =
                StructuralVariationDiscoveryPipelineSpark.broadcastCNVCalls(ctx, getHeaderForReads(),
                        discoverStageArgs.cnvCallsFile);
        final String outputPrefixWithSampleName = getOutputPrefix();
        final SvDiscoveryInputMetaData svDiscoveryInputMetaData =
                new SvDiscoveryInputMetaData(ctx, discoverStageArgs, nonCanonicalChromosomeNamesFile, outputPrefixWithSampleName,
                        null, null, null,
                        cnvCallsBroadcast,
                        getHeaderForReads(), getReference(), getDefaultToolVCFHeaderLines(), localLogger);
        final JavaRDD<GATKRead> assemblyRawAlignments = getReads();

        final AssemblyContigsClassifiedByAlignmentSignatures contigsByPossibleRawTypes =
                preprocess(svDiscoveryInputMetaData, assemblyRawAlignments);

        final List<VariantContext> variants =
                dispatchJobs(ctx, contigsByPossibleRawTypes, svDiscoveryInputMetaData, assemblyRawAlignments, writeSAMFiles);
        contigsByPossibleRawTypes.unpersist();

        final List<VariantContext> filteredVariants =
                AnnotatedVariantProducer.filterMergedVariantList(variants, svDiscoveryInputMetaData.getDiscoverStageArgs());
        final String out = outputPrefixWithSampleName + MERGED_VCF_FILE_NAME;
        SVVCFWriter.writeVCF(filteredVariants, out,
                svDiscoveryInputMetaData.getReferenceData().getReferenceSequenceDictionaryBroadcast().getValue(),
                svDiscoveryInputMetaData.getDefaultToolVCFHeaderLines(),
                svDiscoveryInputMetaData.getToolLogger());
    }


    private void validateParams() {
        discoverStageArgs.validate();
    }

    /**
     * @return prefix of outputs, with {@link #outputPrefix} decorated with sample name and trailing underscore
     */
    private String getOutputPrefix() {
        if ( Files.exists(Paths.get(outputPrefix)) ) {
            if (Files.isDirectory(Paths.get(outputPrefix))) // existing directory
                return outputPrefix + (outputPrefix.endsWith("/") ? "" : "/") + SVUtils.getSampleId(getHeaderForReads()) + "_";
            else
                throw new UserException("Provided prefix for output is pointing to an existing file: " + outputPrefix); // to avoid accidental override of a file
        } else { // prefixForOutput doesn't point to an existing file or directory
            return outputPrefix + (outputPrefix.endsWith("/") ? "" : "_") + SVUtils.getSampleId(getHeaderForReads()) + "_";
        }
    }

    //==================================================================================================================

    public static final class AssemblyContigsClassifiedByAlignmentSignatures {
        private final JavaRDD<AssemblyContigWithFineTunedAlignments> unknown;
        private final JavaRDD<AssemblyContigWithFineTunedAlignments> simple;
        private final JavaRDD<AssemblyContigWithFineTunedAlignments> complex;

        private AssemblyContigsClassifiedByAlignmentSignatures(final JavaRDD<AssemblyContigWithFineTunedAlignments> contigs) {
            unknown = contigs.filter(tig -> tig.getAlignmentSignatureBasicType().equals(UNKNOWN)).cache();
            simple = contigs.filter(tig -> tig.getAlignmentSignatureBasicType().equals(SIMPLE_CHIMERA)).cache();
            complex = contigs.filter(tig -> tig.getAlignmentSignatureBasicType().equals(COMPLEX)).cache();
        }

        public JavaRDD<AssemblyContigWithFineTunedAlignments> getContigsWithSignatureClassifiedAsUnknown() {
            return unknown;
        }

        public JavaRDD<AssemblyContigWithFineTunedAlignments> getContigsWithSignatureClassifiedAsSimpleChimera() {
            return simple;
        }

        public JavaRDD<AssemblyContigWithFineTunedAlignments> getContigsWithSignatureClassifiedAsComplex() {
            return complex;
        }

        public void unpersist() {
            simple.unpersist(false);
            complex.unpersist(false);
            unknown.unpersist(false);
        }

        /**
         * Write SAM file, if requested, for original alignments of contigs recognized as "Ambiguous", "Incomplete", and "MisAssemblySuspect"
         * TODO: 11/17/17 salvation on assembly contigs that 1) has ambiguous "best" configuration, and 2) has incomplete picture; and flag accordingly
         */
        private void writeSAMfilesForUnknown(final String outputPrefix, final JavaRDD<GATKRead> assemblyRawAlignments,
                                             final SAMFileHeader header) {

            final Map<String, ReasonForAlignmentClassificationFailure> tigNameToReason =
                    unknown.mapToPair(tig -> new Tuple2<>(tig.getContigName(), tig.getReasonForAlignmentClassificationFailure())).collectAsMap();

            final Set<String> namesOfInterest = new HashSet<>(tigNameToReason.keySet());

            final List<GATKRead> contigRawAlignments = new ArrayList<>(assemblyRawAlignments
                    .filter(read -> namesOfInterest.contains(read.getName())).collect());
            contigRawAlignments.sort(Comparator.comparing(GATKRead::getName));
            final SAMFileHeader clone = header.clone();
            clone.setSortOrder(SAMFileHeader.SortOrder.queryname);

            final EnumMap<ReasonForAlignmentClassificationFailure, SAMFileWriter> writerForEachCase = new EnumMap<>(ReasonForAlignmentClassificationFailure.class);
            final SAMFileWriterFactory factory = new SAMFileWriterFactory().setCreateIndex(true);
            writerForEachCase.put(ReasonForAlignmentClassificationFailure.AMBIGUOUS,
                    factory.makeSAMOrBAMWriter(clone, true, IOUtils.getPath(outputPrefix + ReasonForAlignmentClassificationFailure.AMBIGUOUS.name() + ".bam"))
            );
            writerForEachCase.put(ReasonForAlignmentClassificationFailure.INCOMPLETE,
                    factory.makeSAMOrBAMWriter(clone, true, IOUtils.getPath(outputPrefix + ReasonForAlignmentClassificationFailure.INCOMPLETE.name() + ".bam"))
            );
            writerForEachCase.put(ReasonForAlignmentClassificationFailure.UNINFORMATIVE,
                    factory.makeSAMOrBAMWriter(clone, true, IOUtils.getPath(outputPrefix + ReasonForAlignmentClassificationFailure.UNINFORMATIVE.name() + ".bam"))
            );

            contigRawAlignments.forEach(read -> {
                final ReasonForAlignmentClassificationFailure reason = tigNameToReason.get(read.getName());
                writerForEachCase.get(reason).addAlignment(read.convertToSAMRecord(header));
            });
            writerForEachCase.values().forEach(SAMFileWriter::close);
        }
    }

    /**
     * First parse the input alignments, then classify the assembly contigs based on their alignment signatures,
     * and return the contigs that are classified together for downstream inference.
     */
    public static AssemblyContigsClassifiedByAlignmentSignatures preprocess(final SvDiscoveryInputMetaData svDiscoveryInputMetaData,
                                                                            final JavaRDD<GATKRead> assemblyRawAlignments) {

        final Broadcast<SAMFileHeader> headerBroadcast = svDiscoveryInputMetaData.getSampleSpecificData().getHeaderBroadcast();
        final Broadcast<Set<String>> canonicalChromosomesBroadcast = svDiscoveryInputMetaData.getReferenceData().getCanonicalChromosomesBroadcast();
        final Logger toolLogger = svDiscoveryInputMetaData.getToolLogger();

        final JavaRDD<AssemblyContigWithFineTunedAlignments> contigsWithChimericAlignmentsReconstructed =
                AssemblyContigAlignmentsRDDProcessor
                        .createOptimalCoverageAlignmentSetsForContigs(assemblyRawAlignments, headerBroadcast.getValue(),
                                canonicalChromosomesBroadcast.getValue(), 0.0, toolLogger)
                        .cache();
        toolLogger.info( contigsWithChimericAlignmentsReconstructed.count() +
                " contigs with chimeric alignments potentially giving SV signals.");

        return new AssemblyContigsClassifiedByAlignmentSignatures(contigsWithChimericAlignmentsReconstructed);
    }

    /**
     * Sends assembly contigs classified based on their alignment signature to
     * a corresponding breakpoint location inference unit.
     *
     * Note that contigs with alignment signature classified as
     * {@link AssemblyContigWithFineTunedAlignments.AlignmentSignatureBasicType#UNKNOWN}
     * currently DO NOT generate any VCF yet.
     */
    public static List<VariantContext> dispatchJobs(final JavaSparkContext ctx,
                                                    final AssemblyContigsClassifiedByAlignmentSignatures contigsByPossibleRawTypes,
                                                    final SvDiscoveryInputMetaData svDiscoveryInputMetaData,
                                                    final JavaRDD<GATKRead> assemblyRawAlignments,
                                                    final boolean writeSAMFiles) {

        final String outputPrefixWithSampleName = svDiscoveryInputMetaData.getOutputPath();

        final List<VariantContext> simpleChimeraVariants =
                extractSimpleVariants(contigsByPossibleRawTypes.simple, svDiscoveryInputMetaData, outputPrefixWithSampleName);

        final CpxAndReInterpretedSimpleVariants complexChimeraVariants =
                extractCpxVariants(ctx, contigsByPossibleRawTypes.complex, svDiscoveryInputMetaData, assemblyRawAlignments, outputPrefixWithSampleName);

        if (writeSAMFiles) {
            contigsByPossibleRawTypes.writeSAMfilesForUnknown(outputPrefixWithSampleName, assemblyRawAlignments,
                    svDiscoveryInputMetaData.getSampleSpecificData().getHeaderBroadcast().getValue());
        }

        final List<VariantContext> inversions = extractInversions();// TODO: 6/29/18 placeholder

        // merged output
        final List<VariantContext> merged = new ArrayList<>(simpleChimeraVariants.size() + complexChimeraVariants.reInterpretedSimpleVariants.size() + inversions.size());
        merged.addAll(simpleChimeraVariants);
        merged.addAll(complexChimeraVariants.reInterpretedSimpleVariants);
        merged.addAll(inversions);
        return merged;
    }

    // return simple variants, including BND's
    private static List<VariantContext> extractSimpleVariants(final JavaRDD<AssemblyContigWithFineTunedAlignments> contigsWithSimpleChimera,
                                                              final SvDiscoveryInputMetaData svDiscoveryInputMetaData,
                                                              final String outputPrefixWithSampleName) {
        final List<VariantContext> simpleVariants =
                SimpleNovelAdjacencyInterpreter.makeInterpretation(contigsWithSimpleChimera, svDiscoveryInputMetaData);
        final Logger logger = svDiscoveryInputMetaData.getDiscoverStageArgs().runInDebugMode ? svDiscoveryInputMetaData.getToolLogger() : null;
        SVVCFWriter.writeVCF(simpleVariants, outputPrefixWithSampleName + SIMPLE_CHIMERA_VCF_FILE_NAME,
                svDiscoveryInputMetaData.getReferenceData().getReferenceSequenceDictionaryBroadcast().getValue(),
                svDiscoveryInputMetaData.getDefaultToolVCFHeaderLines(), logger);
        return simpleVariants;
    }

    private static final class CpxAndReInterpretedSimpleVariants {
        private final List<VariantContext> cpxVariants;
        private final List<VariantContext> reInterpretedSimpleVariants;

        CpxAndReInterpretedSimpleVariants(final List<VariantContext> cpxVariants, final List<VariantContext> reInterpretedSimpleVariants) {
            this.cpxVariants = cpxVariants;
            this.reInterpretedSimpleVariants = reInterpretedSimpleVariants;
        }
    }

    private static CpxAndReInterpretedSimpleVariants extractCpxVariants(final JavaSparkContext ctx,
                                                                        final JavaRDD<AssemblyContigWithFineTunedAlignments> contigsWithCpxAln,
                                                                        final SvDiscoveryInputMetaData svDiscoveryInputMetaData,
                                                                        final JavaRDD<GATKRead> assemblyRawAlignments,
                                                                        final String outputPrefixWithSampleName) {
        final Logger toolLogger = svDiscoveryInputMetaData.getDiscoverStageArgs().runInDebugMode ? svDiscoveryInputMetaData.getToolLogger() : null;
        final Set<VCFHeaderLine> defaultToolVCFHeaderLines = svDiscoveryInputMetaData.getDefaultToolVCFHeaderLines();
        final List<VariantContext> complexVariants =
                CpxVariantInterpreter.makeInterpretation(contigsWithCpxAln, svDiscoveryInputMetaData);
        SVVCFWriter.writeVCF(complexVariants, outputPrefixWithSampleName + COMPLEX_CHIMERA_VCF_FILE_NAME,
                svDiscoveryInputMetaData.getReferenceData().getReferenceSequenceDictionaryBroadcast().getValue(),
                defaultToolVCFHeaderLines, toolLogger);

        final JavaRDD<VariantContext> complexVariantsRDD = ctx.parallelize(complexVariants);
        final SegmentedCpxVariantSimpleVariantExtractor.ExtractedSimpleVariants reInterpretedSimple =
                SegmentedCpxVariantSimpleVariantExtractor.extract(complexVariantsRDD, svDiscoveryInputMetaData, assemblyRawAlignments);
        final SAMSequenceDictionary refSeqDict = svDiscoveryInputMetaData.getReferenceData().getReferenceSequenceDictionaryBroadcast().getValue();
        final String derivedOneSegmentSimpleVCF = outputPrefixWithSampleName + REINTERPRETED_1_SEG_CALL_VCF_FILE_NAME;
        final String derivedMultiSegmentSimpleVCF = outputPrefixWithSampleName + REINTERPRETED_MULTI_SEG_CALL_VCF_FILE_NAME;
        SVVCFWriter.writeVCF(reInterpretedSimple.getReInterpretZeroOrOneSegmentCalls(), derivedOneSegmentSimpleVCF, refSeqDict, defaultToolVCFHeaderLines, toolLogger);
        SVVCFWriter.writeVCF(reInterpretedSimple.getReInterpretMultiSegmentsCalls(), derivedMultiSegmentSimpleVCF, refSeqDict, defaultToolVCFHeaderLines, toolLogger);

        return new CpxAndReInterpretedSimpleVariants(complexVariants, reInterpretedSimple.getMergedReinterpretedCalls());
    }

    // TODO: 6/29/18 when BND variants are interpreted using short read evidence (e.g. EvidenceTargetLinks, resolved inversions), put it here
    private static List<VariantContext> extractInversions() {
        return Collections.emptyList();
    }

    //==================================================================================================================

    //==================================================================================================================

}
