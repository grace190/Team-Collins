package org.broadinstitute.hellbender.utils;

import org.broadinstitute.hellbender.utils.Histogram;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Created by gauthier on 11/1/16.
 */
public class HistogramUnitTest {
    private final Double EPSILON = 0.001;

    @Test
    public void testAdd() throws Exception {
        Histogram bimodalHist = new Histogram();
        for (int i = 0; i <= 100; i++) {
            bimodalHist.add(1 + i / 1000.0);
        }
        Assert.assertEquals(bimodalHist.get(1.0), Integer.valueOf(100), "");
        Assert.assertEquals(bimodalHist.get(1.1), Integer.valueOf(1), "");
    }

    @Test
    public void testAddingQuantizedValues() throws Exception {
        Histogram hist = new Histogram();
        for (int i = 0; i < 100; i++) {
            hist.add(1.2);
        }
        Assert.assertEquals(hist.get(1.2), Integer.valueOf(100));
        Assert.assertEquals(hist.median(), 1.2, EPSILON);
    }

    @Test
    public void testBulkAdd() throws Exception {
        Histogram bimodalHist = new Histogram();
        for (int i = 0; i <= 100; i++) {
            bimodalHist.add(1 + i / 1000.0, 2);
        }
        Assert.assertEquals(bimodalHist.get(1.0), Integer.valueOf(200), "");
        Assert.assertEquals(bimodalHist.get(1.1), Integer.valueOf(2), "");
    }

    @Test
    public void testMedianOfEvens() throws Exception {
        Histogram bimodalHist = new Histogram(1d);
        for (int i = 0; i < 10; i++) {
            bimodalHist.add(1.0);
            bimodalHist.add(16.0);
        }

        Assert.assertEquals(bimodalHist.median(), 8.5, EPSILON, "");
    }

    /**
     * A previous implementation of Histogram used a HashMap().keySet(), assuming
     * sorting, to determine median.  Unfortunately, that isn't guaranteed to be ordered but
     * appears that way for small sets of small numbers.  It is effectively ordered by the 
     * mod16 of the hashcode of the key until there are > 16 keys or there is a collision. 
     * Most tests use a small number of small values so it appears to work.  By using values
     * of 16 we can see that 16 comes before 1 since 16 mod 16 is zero.
     * 
     * This happens with Doubles here because internally Histogram stores these as Integers
     */
    @Test
    public void testMedianEnsureNotHashcodeDependent() throws Exception {
        Histogram bimodalHist = new Histogram(1d);
        bimodalHist.add(1.0,2);
        bimodalHist.add(2.0,2);
        bimodalHist.add(16.0,2);

        Assert.assertEquals(bimodalHist.median(), 2.0, EPSILON, "");
    }

    @Test
    public void testMedianOfOdds() throws Exception {
        Histogram bimodalHist = new Histogram(1d);
        for (int i = 0; i < 10; i++) {
            bimodalHist.add(1.0);
            bimodalHist.add(16.0);
        }
        bimodalHist.add(8.0);

        Assert.assertEquals(bimodalHist.median(), 8.0, EPSILON, "");
    }

    @Test
    public void testMedianOfEmptyHist() throws Exception {
        Histogram empty = new Histogram();
        Assert.assertNull(empty.median());
    }

    @Test
    public void testMedianOfSingleItem() throws Exception {
        Histogram singleItem = new Histogram();
        singleItem.add(20.0);
        Assert.assertEquals(singleItem.median(), 20.0, EPSILON, "");
    }
}

