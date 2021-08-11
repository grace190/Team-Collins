package org.broadinstitute.hellbender.tools.copynumber.formats.collections;

import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.copynumber.GermlineCNVCaller;
import org.broadinstitute.hellbender.tools.copynumber.gcnv.GermlineCNVNamingConstants;
import org.broadinstitute.hellbender.utils.tsv.DataLine;
import org.broadinstitute.hellbender.utils.tsv.TableColumnCollection;

import java.io.File;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A collection representing a real valued vector generated by {@link GermlineCNVCaller}
 *
 * @author Andrey Smirnov &lt;asmirnov@broadinstitute.org&gt;
 */
public class NonLocatableDoubleCollection extends AbstractSampleRecordCollection<Double> {

    private final static String defaultColumnName = GermlineCNVNamingConstants.DEFAULT_GCNV_OUTPUT_COLUMN_PREFIX + "0";

    public NonLocatableDoubleCollection(final File inputFile) {
        super(inputFile,
                new TableColumnCollection(defaultColumnName),
                getDoubleRecordFromDataLineDecoder(),
                getDoubleRecordToDataLineEncoder());
    }

    /**
     * Generates a value from a {@link DataLine} entry read from a denoised copy
     * ratio file generated by `gcnvkernel`.
     */
    private static Function<DataLine, Double> getDoubleRecordFromDataLineDecoder() {
        return dataLine -> {
            try {
                return dataLine.getDouble(defaultColumnName);
            } catch (final IllegalArgumentException ex) {
                throw new UserException.BadInput(String.format("Validation error occurred on line %d of the denoised copy ratio file : ", dataLine.getLineNumber())
                        + ex.getMessage());
            }
        };
    }

    /**
     * Generates an instance of {@link DataLine} for writing denoised copy ratio
     * collection to file.
     */
    private static BiConsumer<Double, DataLine> getDoubleRecordToDataLineEncoder() {
        return (value, dataLine) -> dataLine.append(value);
    }

}
