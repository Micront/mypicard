/*
 * The MIT License
 *
 * Copyright (c) 2011 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package picard.illumina;

import htsjdk.samtools.BAMRecordCodec;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileWriter;
import htsjdk.samtools.SAMFileWriterFactory;
import htsjdk.samtools.SAMReadGroupRecord;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordQueryNameComparator;
import htsjdk.samtools.util.CollectionUtil;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Iso8601Date;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.SortingCollection;
import htsjdk.samtools.util.StringUtil;
import picard.PicardException;
import picard.cmdline.CommandLineProgram;
import picard.cmdline.CommandLineProgramProperties;
import picard.cmdline.Option;
import picard.cmdline.programgroups.Illumina;
import picard.cmdline.StandardOptionDefinitions;
import picard.illumina.parser.ReadStructure;
import picard.illumina.parser.readers.BclQualityEvaluationStrategy;
import picard.util.IlluminaUtil;
import picard.util.IlluminaUtil.IlluminaAdapterPair;
import picard.util.TabbedTextFileWithHeaderParser;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IlluminaBasecallsToSam transforms a lane of Illumina data file formats (bcl, locs, clocs, qseqs, etc.) into
 * SAM or BAM file format.
 * <p/>
 * In this application, barcode data is read from Illumina data file groups, each of which is associated with a tile.
 * Each tile may contain data for any number of barcodes, and a single barcode's data may span multiple tiles.  Once the
 * barcode data is collected from files, each barcode's data is written to its own SAM/BAM.  The barcode data must be
 * written in order; this means that barcode data from each tile is sorted before it is written to file, and that if a
 * barcode's data does span multiple tiles, data collected from each tile must be written in the order of the tiles
 * themselves.
 * <p/>
 * This class employs a number of private subclasses to achieve this goal.  The TileReadAggregator controls the flow
 * of operation.  It is fed a number of Tiles which it uses to spawn TileReaders.  TileReaders are responsible for
 * reading Illumina data for their respective tiles from disk, and as they collect that data, it is fed back into the
 * TileReadAggregator.  When a TileReader completes a tile, it notifies the TileReadAggregator, which reviews what was
 * read and conditionally queues its writing to disk, baring in mind the requirements of write-order described in the
 * previous paragraph.  As writes complete, the TileReadAggregator re-evalutes the state of reads/writes and may queue
 * more writes.  When all barcodes for all tiles have been written, the TileReadAggregator shuts down.
 * <p/>
 * The TileReadAggregator controls task execution using a specialized ThreadPoolExecutor.  It accepts special Runnables
 * of type PriorityRunnable which allow a priority to be assigned to the runnable.  When the ThreadPoolExecutor is
 * assigning threads, it gives priority to those PriorityRunnables with higher priority values.  In this application,
 * TileReaders are assigned lowest priority, and write tasks are assigned high priority.  It is designed in this fashion
 * to minimize the amount of time data must remain in memory (write the data as soon as possible, then discard it from
 * memory) while maximizing CPU usage.
 *
 * @author jburke@broadinstitute.org
 * @author mccowan@broadinstitute.org
 */
@CommandLineProgramProperties(
        usage = IlluminaBasecallsToSam.USAGE,
        usageShort = IlluminaBasecallsToSam.USAGE,
        programGroup = Illumina.class
)
public class IlluminaBasecallsToSam extends CommandLineProgram {
    // The following attributes define the command-line arguments

    public static final String USAGE = "Generate a SAM or BAM file from data in an Illumina basecalls output directory";

    @Option(doc = "The basecalls directory. ", shortName = "B")
    public File BASECALLS_DIR;
    
    @Option(doc = "The barcodes directory with _barcode.txt files (generated by ExtractIlluminaBarcodes). If not set, use BASECALLS_DIR. ", shortName = "BCD", optional = true)
    public File BARCODES_DIR;

    @Option(doc = "Lane number. ", shortName = StandardOptionDefinitions.LANE_SHORT_NAME)
    public Integer LANE;

    @Option(doc = "Deprecated (use LIBRARY_PARAMS).  The output SAM or BAM file. Format is determined by extension.",
            shortName = StandardOptionDefinitions.OUTPUT_SHORT_NAME,
            mutex = {"BARCODE_PARAMS", "LIBRARY_PARAMS"})
    public File OUTPUT;

    @Option(doc = "The barcode of the run.  Prefixed to read names.")
    public String RUN_BARCODE;

    @Option(doc = "Deprecated (use LIBRARY_PARAMS).  The name of the sequenced sample",
            shortName = StandardOptionDefinitions.SAMPLE_ALIAS_SHORT_NAME,
            mutex = {"BARCODE_PARAMS", "LIBRARY_PARAMS"})
    public String SAMPLE_ALIAS;

    @Option(doc = "ID used to link RG header record with RG tag in SAM record.  " +
            "If these are unique in SAM files that get merged, merge performance is better.  " +
            "If not specified, READ_GROUP_ID will be set to <first 5 chars of RUN_BARCODE>.<LANE> .",
            shortName = StandardOptionDefinitions.READ_GROUP_ID_SHORT_NAME, optional = true)
    public String READ_GROUP_ID;

    @Option(doc = "Deprecated (use LIBRARY_PARAMS).  The name of the sequenced library",
            shortName = StandardOptionDefinitions.LIBRARY_NAME_SHORT_NAME,
            optional = true,
            mutex = {"BARCODE_PARAMS", "LIBRARY_PARAMS"})
    public String LIBRARY_NAME;

    @Option(doc = "The name of the sequencing center that produced the reads.  Used to set the RG.CN tag.", optional = true)
    public String SEQUENCING_CENTER = "BI";

    @Option(doc = "The start date of the run.", optional = true)
    public Date RUN_START_DATE;

    @Option(doc = "The name of the sequencing technology that produced the read.", optional = true)
    public String PLATFORM = "illumina";

    @Option(doc = ReadStructure.PARAMETER_DOC, shortName = "RS")
    public String READ_STRUCTURE;

    @Option(doc = "Deprecated (use LIBRARY_PARAMS).  Tab-separated file for creating all output BAMs for barcoded run " +
            "with single IlluminaBasecallsToSam invocation.  Columns are BARCODE, OUTPUT, SAMPLE_ALIAS, and " +
            "LIBRARY_NAME.  Row with BARCODE=N is used to specify a file for no barcode match",
            mutex = {"OUTPUT", "SAMPLE_ALIAS", "LIBRARY_NAME", "LIBRARY_PARAMS"})
    public File BARCODE_PARAMS;

    @Option(doc = "Tab-separated file for creating all output BAMs for a lane with single IlluminaBasecallsToSam " +
            "invocation.  The columns are OUTPUT, SAMPLE_ALIAS, and LIBRARY_NAME, BARCODE_1, BARCODE_2 ... BARCODE_X " +
            "where X = number of barcodes per cluster (optional).  Row with BARCODE_1 set to 'N' is used to specify a file " +
            "for no barcode match.  You may also provide any 2 letter RG header attributes (excluding PU, CN, PL, and" +
            " DT)  as columns in this file and the values for those columns will be inserted into the RG tag for the" +
            " BAM file created for a given row.",
            mutex = {"OUTPUT", "SAMPLE_ALIAS", "LIBRARY_NAME", "BARCODE_PARAMS"})
    public File LIBRARY_PARAMS;

    @Option(doc = "Which adapters to look for in the read.")
    public List<IlluminaAdapterPair> ADAPTERS_TO_CHECK = new ArrayList<IlluminaAdapterPair>(
            Arrays.asList(IlluminaAdapterPair.INDEXED,
                    IlluminaAdapterPair.DUAL_INDEXED,
                    IlluminaAdapterPair.NEXTERA_V2,
                    IlluminaAdapterPair.FLUIDIGM));

    @Option(doc = "The number of threads to run in parallel. If NUM_PROCESSORS = 0, number of cores is automatically set to " +
            "the number of cores available on the machine. If NUM_PROCESSORS < 0, then the number of cores used will" +
            " be the number available on the machine less NUM_PROCESSORS.")
    public Integer NUM_PROCESSORS = 0;

    @Option(doc = "If set, this is the first tile to be processed (used for debugging).  Note that tiles are not processed" +
            " in numerical order.",
            optional = true)
    public Integer FIRST_TILE;

    @Option(doc = "If set, process no more than this many tiles (used for debugging).", optional = true)
    public Integer TILE_LIMIT;

    @Option(doc = "If true, call System.gc() periodically.  This is useful in cases in which the -Xmx value passed " +
            "is larger than the available memory.")
    public Boolean FORCE_GC = true;

    @Option(doc="Apply EAMSS filtering to identify inappropriately quality scored bases towards the ends of reads" +
            " and convert their quality scores to Q2.")
    public boolean APPLY_EAMSS_FILTER = true;

    @Option(doc = "Configure SortingCollections to store this many records before spilling to disk. For an indexed" +
            " run, each SortingCollection gets this value/number of indices.")
    public int MAX_READS_IN_RAM_PER_TILE = 1200000;

    @Option(doc="The minimum quality (after transforming 0s to 1s) expected from reads.  If qualities are lower than this value, an error is thrown." +
            "The default of 2 is what the Illumina's spec describes as the minimum, but in practice the value has been observed lower.")
    public int MINIMUM_QUALITY = BclQualityEvaluationStrategy.ILLUMINA_ALLEGED_MINIMUM_QUALITY;

    @Option(doc="Whether to include non-PF reads", shortName="NONPF", optional=true)
    public boolean INCLUDE_NON_PF_READS = true;

    @Option(doc="Whether to ignore reads whose barcodes are not found in LIBRARY_PARAMS.  Useful when outputting " +
            "BAMs for only a subset of the barcodes in a lane.", shortName="INGORE_UNEXPECTED")
    public boolean IGNORE_UNEXPECTED_BARCODES = false;

    private final Map<String, SAMFileWriterWrapper> barcodeSamWriterMap = new HashMap<String, SAMFileWriterWrapper>();
    private ReadStructure readStructure;
    IlluminaBasecallsConverter<SAMRecordsForCluster> basecallsConverter;
    private static final Log log = Log.getInstance(IlluminaBasecallsToSam.class);
    private BclQualityEvaluationStrategy bclQualityEvaluationStrategy;

    @Override
    protected int doWork() {
        initialize();
        basecallsConverter.doTileProcessing();
        return 0;
    }

    /**
     * Prepares loggers, initiates garbage collection thread, parses arguments and initialized variables appropriately/
     */
    private void initialize() {
        this.bclQualityEvaluationStrategy = new BclQualityEvaluationStrategy(MINIMUM_QUALITY);

        if (OUTPUT != null) {
            IOUtil.assertFileIsWritable(OUTPUT);
        }

        if (LIBRARY_PARAMS != null) {
            IOUtil.assertFileIsReadable(LIBRARY_PARAMS);
        }

        if (OUTPUT != null) {
            barcodeSamWriterMap.put(null, buildSamFileWriter(OUTPUT, SAMPLE_ALIAS, LIBRARY_NAME, buildSamHeaderParameters(null)));
        } else {
            populateWritersFromLibraryParams();
        }

        readStructure = new ReadStructure(READ_STRUCTURE);

        final int numOutputRecords = readStructure.templates.length();

        basecallsConverter = new IlluminaBasecallsConverter<SAMRecordsForCluster>(BASECALLS_DIR, BARCODES_DIR, LANE, readStructure,
                barcodeSamWriterMap, true, MAX_READS_IN_RAM_PER_TILE/numOutputRecords, TMP_DIR, NUM_PROCESSORS, FORCE_GC,
                FIRST_TILE, TILE_LIMIT, new QueryNameComparator(), new Codec(numOutputRecords), SAMRecordsForCluster.class,
                bclQualityEvaluationStrategy, this.APPLY_EAMSS_FILTER, INCLUDE_NON_PF_READS, IGNORE_UNEXPECTED_BARCODES);

        log.info("DONE_READING STRUCTURE IS " + readStructure.toString());

        /**
         * Be sure to pass the outputReadStructure to ClusterDataToSamConverter, which reflects the structure of the output cluster
         * data which may be different from the input read structure (specifically if there are skips).
         */
        final ClusterDataToSamConverter converter = new ClusterDataToSamConverter(RUN_BARCODE, READ_GROUP_ID,
                basecallsConverter.getFactory().getOutputReadStructure(), ADAPTERS_TO_CHECK);
        basecallsConverter.setConverter(converter);

    }

    /**
     * Assert that expectedCols are present and return actualCols - expectedCols
     *
     * @param actualCols   The columns present in the LIBRARY_PARAMS file
     * @param expectedCols The columns that are REQUIRED
     * @return actualCols - expectedCols
     */
    private Set<String> findAndFilterExpectedColumns(final Set<String> actualCols, final Set<String> expectedCols) {
        final Set<String> missingColumns = new HashSet<String>(expectedCols);
        missingColumns.removeAll(actualCols);

        if (!missingColumns.isEmpty()) {
            throw new PicardException(String.format(
                    "LIBRARY_PARAMS file %s is missing the following columns: %s.",
                    LIBRARY_PARAMS.getAbsolutePath(), StringUtil.join(", ", missingColumns
            )));
        }

        final Set<String> remainingColumns = new HashSet<String>(actualCols);
        remainingColumns.removeAll(expectedCols);
        return remainingColumns;
    }

    /**
     * Given a set of columns assert that all columns conform to the format of an RG header attribute (i.e. 2 letters)
     * the attribute is NOT a member of the rgHeaderTags that are built by default in buildSamHeaderParameters
     *
     * @param rgTagColumns A set of columns that should conform to the rg header attribute format
     */
    private void checkRgTagColumns(final Set<String> rgTagColumns) {
        final Set<String> forbiddenHeaders = buildSamHeaderParameters(null).keySet();
        forbiddenHeaders.retainAll(rgTagColumns);

        if (!forbiddenHeaders.isEmpty()) {
            throw new PicardException("Illegal ReadGroup tags in library params(barcode params) file(" + LIBRARY_PARAMS.getAbsolutePath() + ") Offending headers = " + StringUtil.join(", ", forbiddenHeaders));
        }

        for (final String column : rgTagColumns) {
            if (column.length() > 2) {
                throw new PicardException("Column label (" + column + ") unrecognized.  Library params(barcode params) can only contain the columns " +
                        "(OUTPUT, LIBRARY_NAME, SAMPLE_ALIAS, BARCODE, BARCODE_<X> where X is a positive integer) OR two letter RG tags!");
            }
        }
    }

    /**
     * For each line in the LIBRARY_PARAMS file create a SamFileWriter and put it in the barcodeSamWriterMap map, where
     * the key to the map is the concatenation of all sampleBarcodes in order for the given line
     */
    private void populateWritersFromLibraryParams() {
        final TabbedTextFileWithHeaderParser libraryParamsParser = new TabbedTextFileWithHeaderParser(LIBRARY_PARAMS);

        final Set<String> expectedColumnLabels = CollectionUtil.makeSet("OUTPUT", "SAMPLE_ALIAS", "LIBRARY_NAME");
        final List<String> barcodeColumnLabels = new ArrayList<String>();
        if (readStructure.sampleBarcodes.length() == 1) {
            //For the single barcode read case, the barcode label name can either by BARCODE or BARCODE_1
            if (libraryParamsParser.hasColumn("BARCODE")) {
                barcodeColumnLabels.add("BARCODE");
            } else if (libraryParamsParser.hasColumn("BARCODE_1")) {
                barcodeColumnLabels.add("BARCODE_1");
            } else {
                throw new PicardException("LIBRARY_PARAMS(BARCODE_PARAMS) file " + LIBRARY_PARAMS + " does not have column BARCODE or BARCODE_1.");
            }
        } else {
            for (int i = 1; i <= readStructure.sampleBarcodes.length(); i++) {
                barcodeColumnLabels.add("BARCODE_" + i);
            }
        }

        expectedColumnLabels.addAll(barcodeColumnLabels);
        final Set<String> rgTagColumns = findAndFilterExpectedColumns(libraryParamsParser.columnLabels(), expectedColumnLabels);
        checkRgTagColumns(rgTagColumns);

        for (final TabbedTextFileWithHeaderParser.Row row : libraryParamsParser) {
            List<String> barcodeValues = null;

            if (!barcodeColumnLabels.isEmpty()) {
                barcodeValues = new ArrayList<String>();
                for (final String barcodeLabel : barcodeColumnLabels) {
                    barcodeValues.add(row.getField(barcodeLabel));
                }
            }

            final String key = (barcodeValues == null || barcodeValues.contains("N")) ? null : StringUtil.join("", barcodeValues);
            if (barcodeSamWriterMap.containsKey(key)) {    //This will catch the case of having more than 1 line in a non-barcoded LIBRARY_PARAMS file
                throw new PicardException("Row for barcode " + key + " appears more than once in LIBRARY_PARAMS or BARCODE_PARAMS file " +
                        LIBRARY_PARAMS);
            }

            final Map<String, String> samHeaderParams = buildSamHeaderParameters(barcodeValues);

            for (final String tagName : rgTagColumns) {
                samHeaderParams.put(tagName, row.getField(tagName));
            }

            final SAMFileWriterWrapper writer = buildSamFileWriter(new File(row.getField("OUTPUT")),
                    row.getField("SAMPLE_ALIAS"), row.getField("LIBRARY_NAME"), samHeaderParams);
            barcodeSamWriterMap.put(key, writer);
        }
        if (barcodeSamWriterMap.isEmpty()) {
            throw new PicardException("LIBRARY_PARAMS(BARCODE_PARAMS) file " + LIBRARY_PARAMS + " does have any data rows.");
        }
        libraryParamsParser.close();
    }

    /**
     * Create the list of headers that will be added to the SAMFileHeader for a library with the given sampleBarcodes (or
     * the entire run if sampleBarcodes == NULL).  Note that any value that is null will NOT be added via buildSamFileWriter
     * but is placed in the map in order to be able to query the tags that we automatically add.
     *
     * @param barcodes The list of sampleBarcodes that uniquely identify the read group we are building parameters for
     * @return A Map of ReadGroupHeaderTags -> Values
     */
    private Map<String, String> buildSamHeaderParameters(final List<String> barcodes) {
        final Map<String, String> params = new LinkedHashMap<String, String>();

        String platformUnit = RUN_BARCODE + "." + LANE;
        if (barcodes != null) platformUnit += ("." + IlluminaUtil.barcodeSeqsToString(barcodes));

        params.put("PL", PLATFORM);
        params.put("PU", platformUnit);
        params.put("CN", SEQUENCING_CENTER);
        params.put("DT", RUN_START_DATE == null ? null : new Iso8601Date(RUN_START_DATE).toString());

        return params;
    }

    /**
     * Build a SamFileWriter that will write its contents to the output file.
     *
     * @param output           The file to which to write
     * @param sampleAlias      The sample alias set in the read group header
     * @param libraryName      The name of the library to which this read group belongs
     * @param headerParameters Header parameters that will be added to the RG header for this SamFile
     * @return A SAMFileWriter
     */
    private SAMFileWriterWrapper buildSamFileWriter(final File output, final String sampleAlias,
                                                    final String libraryName, final Map<String, String> headerParameters) {
        IOUtil.assertFileIsWritable(output);
        final SAMReadGroupRecord rg = new SAMReadGroupRecord(READ_GROUP_ID);
        rg.setSample(sampleAlias);

        if (libraryName != null) rg.setLibrary(libraryName);
        for (final Map.Entry<String, String> tagNameToValue : headerParameters.entrySet()) {
            if (tagNameToValue.getValue() != null) {
                rg.setAttribute(tagNameToValue.getKey(), tagNameToValue.getValue());
            }
        }

        final SAMFileHeader header = new SAMFileHeader();

        header.setSortOrder(SAMFileHeader.SortOrder.queryname);
        header.addReadGroup(rg);
        return new SAMFileWriterWrapper(new SAMFileWriterFactory().makeSAMOrBAMWriter(header, true, output));
    }

    public static void main(final String[] args) {
        System.exit(new IlluminaBasecallsToSam().instanceMain(args));
    }

    /**
     * Put any custom command-line validation in an override of this method.
     * clp is initialized at this point and can be used to print usage and access args.
     * Any options set by command-line parser can be validated.
     *
     * @return null if command line is valid.  If command line is invalid, returns an array of error message
     *         to be written to the appropriate place.
     */
    @Override
    protected String[] customCommandLineValidation() {
        if (BARCODE_PARAMS != null) {
            LIBRARY_PARAMS = BARCODE_PARAMS;
        }

        final ArrayList<String> messages = new ArrayList<String>();

        readStructure = new ReadStructure(READ_STRUCTURE);
        if (!readStructure.sampleBarcodes.isEmpty()) {
            if (LIBRARY_PARAMS == null) {
                messages.add("BARCODE_PARAMS or LIBRARY_PARAMS is missing.  If READ_STRUCTURE contains a B (barcode)" +
                        " then either LIBRARY_PARAMS or BARCODE_PARAMS(deprecated) must be provided!");
            }
        }

        if (READ_GROUP_ID == null) {
            READ_GROUP_ID = RUN_BARCODE.substring(0, 5) + "." + LANE;
        }
        if (messages.isEmpty()) {
            return null;
        }
        return messages.toArray(new String[messages.size()]);
    }

    private static class SAMFileWriterWrapper
            implements IlluminaBasecallsConverter.ConvertedClusterDataWriter<SAMRecordsForCluster> {
        public final SAMFileWriter writer;

        private SAMFileWriterWrapper(final SAMFileWriter writer) {
            this.writer = writer;
        }

        @Override
        public void write(final SAMRecordsForCluster records) {
            for (final SAMRecord rec : records.records) {
                writer.addAlignment(rec);
            }
        }

        @Override
        public void close() {
            writer.close();
        }
    }

    static class SAMRecordsForCluster {
        final SAMRecord[] records;

        SAMRecordsForCluster(final int numRecords) {
            records = new SAMRecord[numRecords];
        }
    }

    static class QueryNameComparator implements Comparator<SAMRecordsForCluster> {
        private final SAMRecordQueryNameComparator comparator = new SAMRecordQueryNameComparator();
        @Override
        public int compare(final SAMRecordsForCluster s1, final SAMRecordsForCluster s2) {
            return comparator.compare(s1.records[0], s2.records[0]);
        }
    }

    static class Codec implements SortingCollection.Codec<SAMRecordsForCluster> {
        private final BAMRecordCodec bamCodec;
        private final int numRecords;

        Codec(final int numRecords, final BAMRecordCodec bamCodec) {
            this.numRecords = numRecords;
            this.bamCodec = bamCodec;
        }

        Codec(final int numRecords) {
            this(numRecords, new BAMRecordCodec(null));
        }

        @Override
        public void setOutputStream(final OutputStream os) {
            bamCodec.setOutputStream(os);
        }

        @Override
        public void setInputStream(final InputStream is) {
            bamCodec.setInputStream(is);
        }

        @Override
        public void encode(final SAMRecordsForCluster val) {
            if (val.records.length != numRecords) {
                throw new IllegalStateException(String.format("Expected number of clusters %d != actual %d",
                        numRecords, val.records.length));
            }
            for (final SAMRecord rec : val.records) {
                bamCodec.encode(rec);
            }
        }

        @Override
        public SAMRecordsForCluster decode() {
            final SAMRecord zerothRecord = bamCodec.decode();
            if (zerothRecord == null) return null;
            final SAMRecordsForCluster ret = new SAMRecordsForCluster(numRecords);
            ret.records[0] = zerothRecord;
            for (int i = 1; i < numRecords; ++i) {
                ret.records[i] = bamCodec.decode();
                if (ret.records[i] == null) {
                    throw new IllegalStateException(String.format("Expected to read %d records but read only %d", numRecords, i));
                }
            }
            return ret;
        }

        @Override
        public SortingCollection.Codec<SAMRecordsForCluster> clone() {
            return new Codec(numRecords, bamCodec.clone());
        }
    }
}
