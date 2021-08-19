import java.io.FileWriter;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.pharmgkb.pharmcat.ParseException;
import org.pharmgkb.pharmcat.PharmCAT;
import org.pharmgkb.pharmcat.VcfTestUtils;
import org.pharmgkb.pharmcat.reporter.ReportContext;
import org.pharmgkb.pharmcat.reporter.model.MessageAnnotation;
import org.pharmgkb.pharmcat.reporter.model.VariantReport;
import org.pharmgkb.pharmcat.reporter.model.result.DrugReport;
import org.pharmgkb.pharmcat.reporter.model.result.GeneReport;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Test the data generated from a full run of the PharmCAT matcher and reporter.
 *
 * @author Ryan Whaley
 */
class PharmCATTest {

  private static final String sf_outsideCalls = "##Test Outside Call Data\n" +
      "#Gene\tDiplotype\tdiplotype activity\tdiplotype calling notes\tjaccard\tpart\tpValue\tROI notes\tspecial case\tnomenclature version\n" +
      "CYP2D6\tCYP2D6*1/CYP2D6*4\t?/?\t\t0.6\t0.75\tp: 0.0\t\t\tv1.9-2017_02_09\n";
  private static final String sf_otherOutsideCalls = "CYP2D6\t*3/*4\nG6PD\tB (wildtype)/B (wildtype)\n";
  private static final String sf_diplotypesTemplate = "\nmatcher: %s\nreporter: %s\nprint (displayCalls): %s";
  private static PharmCATTestWrapper s_pharmcatTopMatch;
  private static PharmCATTestWrapper s_pharmcatAllMatches;
  private static Path s_outsideCallFilePath;
  private static Path s_otherOutsideCallFilePath;

  @BeforeAll
  static void prepare() throws IOException {

    s_outsideCallFilePath = Files.createTempFile("outsideCall", ".tsv");
    try (FileWriter fw = new FileWriter(s_outsideCallFilePath.toFile())) {
      fw.write(sf_outsideCalls);
    }

    s_otherOutsideCallFilePath = Files.createTempFile("otherOutsideCall", ".tsv");
    try (FileWriter fw = new FileWriter(s_otherOutsideCallFilePath.toFile())) {
      fw.write(sf_otherOutsideCalls);
    }

    Path tempDirPath = Files.createTempDirectory(MethodHandles.lookup().lookupClass().getName());
    s_pharmcatTopMatch = new PharmCATTestWrapper(tempDirPath, false);
    s_pharmcatAllMatches = new PharmCATTestWrapper(tempDirPath, true);
  }

  /**
   * NOTE: if these assertions fail then new data may have been added from the DataManager because of an update to the
   * CPIC database. If that's true, then update these numbers to the current count. If the count changes with no known
   * change to the CPIC database then something may be wrong in code.
   */
  @Test
  void testCounts() {
    assertEquals(17, s_pharmcatTopMatch.getContext().getGeneReports().size());
    assertEquals(53, s_pharmcatTopMatch.getContext().getDrugReports().size());
  }

  /**
   * This test illustrates when one gene in a two-gene guideline (amitriptyline) is not called that it should still be
   * able to come up with a matched group
   */
  @Test
  void testCyp2c19_1() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp2c19.singleGeneMatch", new String[]{
            "cyp2c19/s1s1.vcf"
        },
        s_otherOutsideCallFilePath);

    s_pharmcatTopMatch.testCalledByMatcher("CYP2C19");
    s_pharmcatTopMatch.testPrintCalls( "CYP2C19", "*1/*1");

    s_pharmcatTopMatch.testMatchedGroups("amitriptyline", 1);
    s_pharmcatTopMatch.testMatchedGroups("citalopram", 1);
    s_pharmcatTopMatch.testMatchedGroups("ivacaftor", 0);
  }

  @Test
  void testCyp2c19_noCyp2d6() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp2c19.singleGeneMatch", new String[]{
            "cyp2c19/s1s1.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("CYP2C19");
    s_pharmcatTopMatch.testPrintCalls( "CYP2C19", "*1/*1");

    s_pharmcatTopMatch.testNotCalledByMatcher("CYP2D6");

    s_pharmcatTopMatch.testMatchedGroups("amitriptyline", 1);
    s_pharmcatTopMatch.testMatchedGroups("citalopram", 1);
    s_pharmcatTopMatch.testMatchedGroups("ivacaftor", 0);
  }

  /**
   * This test case demos that an "ambiguity" {@link MessageAnnotation} which specifies a variant and a diplotype call
   * for a given drug report will be matched and added to the {@link DrugReport}
   */
  @Test
  void testCyp2c19_s1s2rs58973490het() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp2c19.singleGeneMatch", new String[]{
            "cyp2c19/s1s2rs58973490het.vcf"
        },
        s_otherOutsideCallFilePath);

    s_pharmcatTopMatch.testCalledByMatcher("CYP2C19");
    s_pharmcatTopMatch.testPrintCalls( "CYP2C19", "*1/*2");

    s_pharmcatTopMatch.testMatchedGroups("amitriptyline", 1);
    s_pharmcatTopMatch.testMatchedGroups("citalopram", 1);
    s_pharmcatTopMatch.testMatchedGroups("clomipramine", 1);
    s_pharmcatTopMatch.testMatchedGroups("ivacaftor", 0);

    GeneReport cyp2c19report = s_pharmcatTopMatch.getContext().getGeneReport("CYP2C19");

    VariantReport vr = cyp2c19report.findVariantReport("rs58973490")
        .orElseThrow(() -> new RuntimeException("Variant missing from test data"));
    assertTrue(vr.isHetCall());

    assertEquals(1, cyp2c19report.getMessages().stream()
        .filter(m -> m.getExceptionType().equals(MessageAnnotation.TYPE_AMBIGUITY) && m.getMatches().getVariant().equals("rs58973490"))
        .count());

    assertTrue(s_pharmcatTopMatch.getContext().getDrugReports().stream()
        .filter(r -> r.getRelatedDrugs().contains("amitriptyline"))
        .flatMap(r -> r.getMessages().stream())
        .allMatch(m -> m.getMatches().getVariant().equals("rs58973490") && m.getMatches().getDips().contains("*1/*2") && m.getExceptionType().equals("ambiguity")));
  }

  /**
   * This test case demos that an "ambiguity" {@link MessageAnnotation} which specifies a variant and a diplotype call
   * for a given drug report will not be matched when the variant in the message is homozygous
   */
  @Test
  void testCyp2c19_s1s2() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp2c19.singleGeneMatch", new String[]{
            "cyp2c19/s1s2.vcf"
        },
        s_otherOutsideCallFilePath);

    s_pharmcatTopMatch.testCalledByMatcher("CYP2C19");
    s_pharmcatTopMatch.testPrintCalls( "CYP2C19", "*1/*2");

    s_pharmcatTopMatch.testMatchedGroups("amitriptyline", 1);
    s_pharmcatTopMatch.testMatchedGroups("citalopram", 1);
    s_pharmcatTopMatch.testMatchedGroups("clomipramine", 1);
    s_pharmcatTopMatch.testMatchedGroups("ivacaftor", 0);

    GeneReport cyp2c19report = s_pharmcatTopMatch.getContext().getGeneReport("CYP2C19");

    VariantReport vr = cyp2c19report.findVariantReport("rs58973490")
        .orElseThrow(() -> new RuntimeException("Variant missing from test data"));
    assertFalse(vr.isHetCall());

    assertEquals(0, cyp2c19report.getMessages().stream()
        .filter(m -> m.getExceptionType().equals(MessageAnnotation.TYPE_AMBIGUITY) && m.getMatches().getVariant().equals("rs58973490"))
        .count());

    assertEquals(0, s_pharmcatTopMatch.getContext().getDrugReports().stream()
        .filter(r -> r.getRelatedDrugs().contains("amitriptyline"))
        .mapToLong(r -> r.getMessages().size())
        .sum());
  }

  @Test
  void testClomipramineCall() throws Exception {
    s_pharmcatTopMatch.execute("test.clomipramine", new String[]{
            "cyp2c19/s2s2.vcf"
        },
        s_outsideCallFilePath);

    s_pharmcatTopMatch.testCalledByMatcher("CYP2C19");
    s_pharmcatTopMatch.testPrintCalls( "CYP2C19", "*2/*2");

    s_pharmcatTopMatch.testMatchedGroups("amitriptyline", 1);
    s_pharmcatTopMatch.testMatchedGroups("clomipramine", 1);
    s_pharmcatTopMatch.testMatchedGroups("desipramine", 1);
    s_pharmcatTopMatch.testMatchedGroups("doxepin", 1);
    s_pharmcatTopMatch.testMatchedGroups("imipramine", 1);
    s_pharmcatTopMatch.testMatchedGroups("nortriptyline", 1);
    s_pharmcatTopMatch.testMatchedGroups("trimipramine", 1);

    s_pharmcatTopMatch.testMatchedGroups("clopidogrel", 1);

    s_pharmcatTopMatch.testMatchedGroups("lansoprazole", 1);

    // voriconazole has 2 populations with recommendations so should have 2 matching groups
    s_pharmcatTopMatch.testMatchedGroups("voriconazole", 2);
  }

  @Test
  void testCyp2c19noCall() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp2c19.noCall", new String[]{
            "cyp2c19/noCall.vcf"
        },
        null);


    s_pharmcatTopMatch.testNotCalledByMatcher("CYP2C19");

    s_pharmcatTopMatch.testMatchedGroups("citalopram", 0);
    s_pharmcatTopMatch.testMatchedGroups("ivacaftor", 0);
  }

  @Test
  void testMultipleCallsOnOneGene() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp2c19.rs28399504missing", new String[]{
            "cyp2c19/s4bs17rs28399504missing.vcf"
        },
        null);


    s_pharmcatTopMatch.testCalledByMatcher("CYP2C19");
    s_pharmcatTopMatch.testPrintCalls("CYP2C19", "*4/*4", "*4/*17", "*17/*17");

    s_pharmcatTopMatch.testMatchedGroups("citalopram", 3);
  }

  @Test
  void testCyp2c19_with_outsideCall() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp2c19.s1s4b", new String[]{
        "cyp2c19/s4s17het.vcf"
        },
        s_outsideCallFilePath);

    s_pharmcatTopMatch.testCalledByMatcher("CYP2C19", "CYP2D6");

    s_pharmcatTopMatch.testPrintCalls("CYP2D6", "*1/*4");
    s_pharmcatTopMatch.testPrintCalls("CYP2C19", "*4/*17");

    assertTrue(s_pharmcatTopMatch.getContext().getGeneReport("CYP2D6").isOutsideCall());
  }

  @Test
  void testCyp2c19s4s17() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp2c19s4s17", new String[]{
        "cyp2c19/s1s4s17.vcf"
        },
        s_outsideCallFilePath);

    s_pharmcatTopMatch.testCalledByMatcher("CYP2C19", "CYP2D6");

    s_pharmcatTopMatch.testPrintCalls("CYP2D6", "*1/*4");
    s_pharmcatTopMatch.testPrintCalls("CYP2C19", "*1/*4");

    assertTrue(s_pharmcatTopMatch.getContext().getGeneReport("CYP2D6").isOutsideCall());
  }

  @Test
  void testCftrRefRef() throws Exception {
    s_pharmcatTopMatch.execute("test.cftr.ref_ref", new String[]{
            "cftr/refref.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("CFTR");
    s_pharmcatTopMatch.testPrintCalls("CFTR", "No CPIC variants found");
    s_pharmcatTopMatch.testLookup("CFTR", "ivacaftor non-responsive CFTR sequence");

    s_pharmcatTopMatch.testMatchedGroups("ivacaftor", 1);
  }

  @Test
  void testCyp2c19NoCall() throws Exception {
    s_pharmcatTopMatch.execute("test.cftr.ref_ref", new String[]{
            "DPYD/c1679c1156.vcf"
        },
        s_outsideCallFilePath);

    s_pharmcatTopMatch.testCalledByMatcher("CYP2D6");
    s_pharmcatTopMatch.testPrintCalls("CYP2D6", "*1/*4");
    s_pharmcatTopMatch.testLookup("CYP2D6", "*1", "*4");

    s_pharmcatTopMatch.testMatchedGroups("amitriptyline", 1);
  }

  @Test
  void testCftrF508() throws Exception {
    s_pharmcatTopMatch.execute("test.cftr.refF508del", new String[]{
            "cftr/refF508del.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("CFTR");
    s_pharmcatTopMatch.testPrintCalls("CFTR", "No CPIC variants found");
    s_pharmcatTopMatch.testLookup("CFTR", "ivacaftor non-responsive CFTR sequence");
  }

  @Test
  void testCftrF508HomCTT() throws Exception {
    s_pharmcatTopMatch.execute("test.cftr.F508delHom_CTT", new String[]{
            "cftr/F508delF508del.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("CFTR");
    s_pharmcatTopMatch.testPrintCalls("CFTR", "No CPIC variants found");
    s_pharmcatTopMatch.testLookup("CFTR", "ivacaftor non-responsive CFTR sequence");
  }

  /**
   * This is the same test case as {@link PharmCATTest#testCftrF508()} but the position lines have been sorted
   * lexigraphically. This should not affect the calling and should lead to same diplotype output.
   */
  @Test
  void testCftrF508Sorted() throws Exception {
    s_pharmcatTopMatch.execute("test.cftr.refF508del_sorted", new String[]{
            "cftr/refF508del_sorted.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("CFTR");
    s_pharmcatTopMatch.testPrintCalls("CFTR", "No CPIC variants found");
    s_pharmcatTopMatch.testLookup("CFTR", "ivacaftor non-responsive CFTR sequence");
  }

  @Test
  void testSlco1b1Test1() throws Exception {
    s_pharmcatTopMatch.execute("test.slco1b1.17.21", new String[]{
            "SLCO1B1/s17s21.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("SLCO1B1");
    s_pharmcatTopMatch.testPrintCalls("SLCO1B1", "*17/*21");
    s_pharmcatTopMatch.testLookup("SLCO1B1", "*17", "*21");
  }

  @Test
  void testSlco1b1HomWild() throws Exception {
    s_pharmcatTopMatch.execute("test.slco1b1.hom.wild", new String[]{
            "SLCO1B1/s1as1a.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("SLCO1B1");
    s_pharmcatTopMatch.testPrintCalls("SLCO1B1", "*1A/*1A");
    s_pharmcatTopMatch.testLookup("SLCO1B1", "*1A");

    GeneReport slco1b1Report = s_pharmcatTopMatch.getContext().getGeneReport("SLCO1B1");
    assertTrue(slco1b1Report.getHighlightedVariants().contains("rs4149056T/rs4149056T"));
  }

  @Test
  void testSlco1b1HomVar() throws Exception {
    s_pharmcatTopMatch.execute("test.slco1b1.hom.var", new String[]{
            "SLCO1B1/s5s15.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("SLCO1B1");
    s_pharmcatTopMatch.testPrintCalls("SLCO1B1", "*5/*15");
    s_pharmcatTopMatch.testLookup("SLCO1B1", "*5", "*15");
  }

  @Test
  void testSlco1b1Test3() throws Exception {
    s_pharmcatTopMatch.execute("test.slco1b1.1a.15", new String[]{
            "SLCO1B1/s1as15.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("SLCO1B1");
    s_pharmcatTopMatch.testPrintCalls("SLCO1B1", "*1A/*15");
    s_pharmcatTopMatch.testLookup("SLCO1B1", "*1A", "*15");
  }

  @Test
  void testSlco1b1TestMissing() throws Exception {
    s_pharmcatTopMatch.execute("test.slco1b1.missing", new String[]{
            "DPYD/s1s1.vcf",
            "TPMT/s1s1.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("DPYD", "TPMT");
    s_pharmcatTopMatch.testNotCalledByMatcher("SLCO1B1");
  }

  @Test
  void testDpydS1S2B() throws Exception {
    s_pharmcatTopMatch.execute("test.slco1b1.missing", new String[]{
            "DPYD/s1s2b.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("DPYD");
    s_pharmcatTopMatch.testPrintCalls("DPYD", "c.1627A>G (*5)/c.1905+1G>A (*2A)");
    s_pharmcatTopMatch.testLookup("DPYD", "c.1627A>G (*5)", "c.1905+1G>A (*2A)");

    s_pharmcatTopMatch.testMatchedGroups("fluorouracil", 1);
    s_pharmcatTopMatch.testMatchedGroups("capecitabine", 1);
  }

  @Test
  void testDpydC2846het() throws Exception {
    s_pharmcatTopMatch.execute("test.dpyd.c2846het", new String[]{
            "DPYD/c2846het.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("DPYD");
    s_pharmcatTopMatch.testPrintCalls("DPYD", "Reference/c.2846A>T");
    s_pharmcatTopMatch.testLookup("DPYD", "Reference", "c.2846A>T");

    s_pharmcatTopMatch.testMatchedGroups("fluorouracil", 1);
    s_pharmcatTopMatch.testMatchedGroups("capecitabine", 1);
  }

  /**
   * This tests a special case of SLCO1B1. The gene in this scenario is "uncalled" by the matcher due the sample VCF
   * data. However, SLCO1B1 has an override that will display the rs4149056 diplotype regardless of call status. That
   * same override will assign alleles to use for recommendation lookup
   */
  @Test
  void testSlco1b1TestMulti() throws Exception {
    s_pharmcatTopMatch.execute("test.slco1b1.multi", new String[]{
            "SLCO1B1/multi.vcf"
        },
        null);

    s_pharmcatTopMatch.testNotCalledByMatcher("SLCO1B1");
    s_pharmcatTopMatch.testPrintCalls("SLCO1B1", "rs4149056T/rs4149056C");
    s_pharmcatTopMatch.testLookup("SLCO1B1", "*1A", "*5");

    s_pharmcatTopMatch.testMatchedGroups("simvastatin", 1);
  }

  @Test
  void testUgt1a1PhasedMulti() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.phased.multi", new String[]{
            "UGT1A1/s1s60s80phased.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*1/*80");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*1", "*80");

    assertTrue(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1UnphasedMulti() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.unphased.multi", new String[]{
            "UGT1A1/s1s60s80unphased.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*80 (heterozygous)");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*1", "*1");

    assertFalse(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1S1S28S60S80() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s1s28s60s80unphased", new String[]{
            "UGT1A1/s1s28s60s80unphased.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*80+*28 (heterozygous)");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*1", "*80");

    assertFalse(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1S28S37() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s28s37", new String[]{
            "UGT1A1/s28s37.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*28 (heterozygous)", "*37 (heterozygous)");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*80", "*80");

    assertFalse(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1s28s80phased() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s28s80phased", new String[]{
            "UGT1A1/s28s80phased.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*1/*80+*28");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*1", "*80+*28");

    // the guideline should have a matching message
    assertTrue(s_pharmcatTopMatch.getContext().getDrugReports().stream()
        .filter(r -> r.getRelatedDrugs().contains("atazanavir"))
        .allMatch(r -> r.getMessages().size() == 1));

    assertTrue(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1s28s80s6s60phased() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s28s80s6s60phased", new String[]{
            "UGT1A1/s28s80s6s60phased.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*6/*80+*28");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*6", "*80+*28");

    assertTrue(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1s28s80s6s60unphased() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s28s80s6s60unphased", new String[]{
            "UGT1A1/s28s80s6s60unphased.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*80+*28 (heterozygous)", "*6 (heterozygous)");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*80", "*80");

    assertFalse(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1s28s80unphased() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s28s80unphased", new String[]{
            "UGT1A1/s28s80unphased.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*80+*28 (heterozygous)");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*1", "*80");

    assertFalse(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1s6s6() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s6s6", new String[]{
            "UGT1A1/s6s6.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*6/*6");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*6", "*6");

    assertTrue(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1s6s60s80s28MissingPhased() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s6s60s80s28MissingPhased", new String[]{
            "UGT1A1/s6s60s80s28missingphased.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*6/*80", "*6/*80+*28", "*6/*80+*37");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*80", "*80");

    assertTrue(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1s6s60s80s28MissingUnphased() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s6s60s80s28MissingUnphased", new String[]{
            "UGT1A1/s6s60s80s28missingunphased.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*6 (heterozygous)","*80 (heterozygous)","*80+*28 (heterozygous)","*80+*37 (heterozygous)");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*80", "*80");

    assertFalse(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1s80s28missing() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s80s28missing", new String[]{
            "UGT1A1/s80s28missing.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*80 (heterozygous)", "*80+*28 (heterozygous)", "*80+*37 (heterozygous)");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*1", "*80");

    assertFalse(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1na12717() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.na12717", new String[]{
            "UGT1A1/NA12717_UGT1A1.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*80 (heterozygous)", "*80+*28 (heterozygous)");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*1", "*80");

    assertFalse(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1na18868() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.na18868", new String[]{
            "UGT1A1/NA18868_UGT1A1.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*80 (heterozygous)", "*80+*28 (heterozygous)");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*1", "*80");

    assertFalse(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1na19785() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.na19785", new String[]{
            "UGT1A1/NA19785_UGT1A1.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*80+*28 (heterozygous)", "*80 (heterozygous)");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*1", "*80");

    assertFalse(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1s28homMissing() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s28s28unphaseds60s80miss", new String[]{
            "UGT1A1/s28s28unphaseds60s80miss.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*28/*28", "*28/*80+*28", "*80+*28/*80+*28");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*80", "*80");

    // sample is effectively phased since all positions homozygous
    assertTrue(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1s28s60Hom() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s28s60hom", new String[]{
            "UGT1A1/s28s60hom.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*28 (heterozygous)");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*1", "*80");

    // sample is effectively phased since all positions homozygous
    assertFalse(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1s27s28unphaseds80s60missing() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s27s28unphaseds80s60missing", new String[]{
            "UGT1A1/s27s28unphaseds80s60missing.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*27 (heterozygous)", "*28 (heterozygous)", "*80+*28 (heterozygous)");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*80", "*80");

    assertFalse(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1s28hets60homounphaseds80missing() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s28hets60homounphaseds80missing", new String[]{
            "UGT1A1/s28hets60homounphaseds80missing.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*28 (heterozygous)", "*80+*28 (heterozygous)");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*1", "*80");

    assertFalse(s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1").isPhased());
  }

  @Test
  void testUgt1a1HG00436() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.HG00436", new String[]{
            "UGT1A1/HG00436.vcf"
        },
        null);

    s_pharmcatTopMatch.testNotCalledByMatcher("UGT1A1");

    GeneReport geneReport = s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1");
    assertTrue(geneReport.isPhased());
  }

  @Test
  void testUgt1a1s1s80s27s60s28missingphased() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s1s80s27s60s28missingphased", new String[]{
            "UGT1A1/s1s80s27s60s28missingphased.vcf"
        },
        null);

    s_pharmcatTopMatch.testNotCalledByMatcher("UGT1A1");

    GeneReport geneReport = s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1");
    assertTrue(geneReport.isPhased());
  }

  @Test
  void testUgt1a1s1s60s80s6phased() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s1s60s80s6phased", new String[]{
            "UGT1A1/s1s60s80s6phased.vcf"
        },
        null);

    s_pharmcatTopMatch.testNotCalledByMatcher("UGT1A1");

    GeneReport geneReport = s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1");
    assertTrue(geneReport.isPhased());
  }

  @Test
  void testUgt1a1s1s60s80s28s6phased() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s1s60s80s28s6phased", new String[]{
            "UGT1A1/s1s60s80s28s6phased.vcf"
        },
        null);

    s_pharmcatTopMatch.testNotCalledByMatcher("UGT1A1");

    GeneReport geneReport = s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1");
    assertTrue(geneReport.isPhased());
  }

  @Test
  void testUgt1a1s1s37s80s60phased() throws Exception {
    s_pharmcatTopMatch.execute("test.ugt1a1.s1s37s80s60phased", new String[]{
            "UGT1A1/s1s37s80s60phased.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("UGT1A1");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*1/*80+*37");
    s_pharmcatTopMatch.testLookup("UGT1A1", "*1", "*80+*37");

    GeneReport geneReport = s_pharmcatTopMatch.getContext().getGeneReport("UGT1A1");
    assertTrue(geneReport.isPhased());
  }

  @Test
  void testCyp3a5Missing3Message() throws Exception {
    String gene = "CYP3A5";

    s_pharmcatTopMatch.execute("test.cyp3a5.s3missing", new String[]{
            "cyp3a5/s1s1rs776746missing.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher(gene);
    s_pharmcatTopMatch.testPrintCalls(gene, "*1/*1");
    s_pharmcatTopMatch.testLookup(gene, "*1", "*1");

    // rs776746 should be missing from this report
    assertNotNull(s_pharmcatTopMatch.getContext().getGeneReport(gene).getVariantReports());
    assertTrue(s_pharmcatTopMatch.getContext().getGeneReport(gene).getVariantReports().stream().anyMatch(v -> v.isMissing() && v.getDbSnpId().equals("rs776746")));

    // the guideline should have a matching message
    assertTrue(s_pharmcatTopMatch.getContext().getDrugReports().stream()
        .filter(r -> r.getRelatedDrugs().contains("tacrolimus"))
        .allMatch(r -> r.getMessages().size() > 0));

    assertTrue(s_pharmcatTopMatch.getContext().getGeneReport(gene).isPhased());
  }

  @Test
  void testCyp3a5MissingRS776746() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp3a5.missingRs776746", new String[]{
            "cyp3a5/s1s1rs776746missing.vcf"
        },
        null);
    
    s_pharmcatTopMatch.testCalledByMatcher("CYP3A5");
    s_pharmcatTopMatch.testPrintCalls("CYP3A5", "*1/*1");
  }

  @Test
  void testCyp3a5v1() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp3a5.s1s3rs776746rs55965422het", new String[]{
            "cyp3a5/s1s3rs776746rs55965422het.vcf"
        },
        null);
    
    s_pharmcatTopMatch.testCalledByMatcher("CYP3A5");
    s_pharmcatTopMatch.testPrintCalls("CYP3A5", "*1/*3");
  }

  @Test
  void testCyp3a5v2() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp3a5.s1s3rs776746rs55965422rs28383479het", new String[]{
            "cyp3a5/s1s3rs776746rs55965422rs28383479het.vcf"
        },
        null);
    
    s_pharmcatTopMatch.testCalledByMatcher("CYP3A5");
    s_pharmcatTopMatch.testPrintCalls("CYP3A5", "*1/*3");
  }

  @Test
  void testCyp3a5v3() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp3a5.s3s3rs55965422het", new String[]{
            "cyp3a5/s3s3rs55965422het.vcf"
        },
        null);
    
    s_pharmcatTopMatch.testCalledByMatcher("CYP3A5");
    s_pharmcatTopMatch.testPrintCalls("CYP3A5", "*3/*3");
  }

  @Test
  void testCyp3a5v4() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp3a5.s3s5-homozygous", new String[]{
            "cyp3a5/s3s5-homozygous.vcf"
        },
        null);
    
    s_pharmcatTopMatch.testCalledByMatcher("CYP3A5");
    s_pharmcatTopMatch.testPrintCalls("CYP3A5", "*3/*5");
  }

  @Test
  void testCyp3a5v5() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp3a5.s1s3rs776746rs28383479het", new String[]{
            "cyp3a5/s1s3rs776746rs28383479het.vcf"
        },
        null);
    
    s_pharmcatTopMatch.testCalledByMatcher("CYP3A5");
    s_pharmcatTopMatch.testPrintCalls("CYP3A5", "*1/*3");
  }

  @Test
  void testTpmtStar1s() throws Exception {
    s_pharmcatTopMatch.execute("test.tpmt.star1s", new String[]{
            "TPMT/s1ss1ss3.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("TPMT");
    s_pharmcatTopMatch.testPrintCalls("TPMT", "*1/*3A");
    s_pharmcatTopMatch.testLookup("TPMT", "*1", "*3A");

    GeneReport tpmtReport = s_pharmcatTopMatch.getContext().getGeneReport("TPMT");
    assertEquals(43, tpmtReport.getVariantReports().size());

    assertEquals(0, tpmtReport.getHighlightedVariants().size());
  }

  @Test
  void testTpmtS15OffData() throws Exception {
    s_pharmcatTopMatch.execute("test.tpmt.s15offdata", new String[] {
            "TPMT/s15offdata.vcf"
        },
        null);

    s_pharmcatTopMatch.testNotCalledByMatcher("TPMT");
    GeneReport report = s_pharmcatTopMatch.getContext().getGeneReport("TPMT");
    assertTrue(report.getVariantReports().stream().filter(r -> r.getPosition() == 18133890).allMatch(VariantReport::isMismatch));
  }


  @Test
  void testCyp2c9star61() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp2c9.s1s61", new String[] {
            "cyp2c9/s1s61.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("CYP2C9");
    s_pharmcatTopMatch.testPrintCalls("CYP2C9", "*1/*61");
    s_pharmcatTopMatch.testLookup("CYP2C9", "*1", "*61");

    s_pharmcatTopMatch.testMatchedGroups("lornoxicam", 1);
  }

  @Test
  void testCyp2c9star1Hom() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp2c9.s1s1", new String[] {
            "cyp2c9/s1s1.vcf"
        },
        null);

    s_pharmcatTopMatch.testCalledByMatcher("CYP2C9");
    s_pharmcatTopMatch.testPrintCalls("CYP2C9", "*1/*1");
    s_pharmcatTopMatch.testLookup("CYP2C9", "*1", "*1");

    s_pharmcatTopMatch.testMatchedGroups("celecoxib", 1);
    s_pharmcatTopMatch.testMatchedGroups("ibuprofen", 1);
    s_pharmcatTopMatch.testMatchedGroups("lornoxicam", 1);
  }


  /**
   * Test CYP2B6 for a het *34 sample file. When doing the "top match" scenario this will only match to a 1/34 and,
   * thus, only match to a single recommendation. This test will have a different outcome when run in "all matches" mode
   * and should be compared with {@link #testCyp2b6star1star34AllMatch()}.
   */
  @Test
  void testCyp2b6star1star34() throws Exception {
    s_pharmcatTopMatch.execute("test.cyp2b6.s1s34", new String[] {
            "cyp2b6/s1s34.vcf"
        },
        null);
    s_pharmcatTopMatch.testCalledByMatcher("CYP2B6");
    s_pharmcatTopMatch.testPrintCalls("CYP2B6", "*1/*34");
    s_pharmcatTopMatch.testLookup("CYP2B6", "*1", "*34");

    s_pharmcatTopMatch.testMatchedGroups("efavirenz", 1);
  }

  /**
   * This test is just like {@link #testCyp2b6star1star34()} but run in "all matches" mode. This should result in 2
   * possible different calls coming from the matcher. These two have different phenotypes and, thus, match to different
   * recommendations.
   */
  @Test
  void testCyp2b6star1star34AllMatch() throws Exception {
    s_pharmcatAllMatches.execute("test.cyp2b6.s1s34.allMatch", new String[] {
            "cyp2b6/s1s34.vcf"
        },
        null);
    s_pharmcatAllMatches.testCalledByMatcher("CYP2B6");
    s_pharmcatAllMatches.testPrintCalls("CYP2B6", "*1/*34", "*33/*36");
    s_pharmcatAllMatches.testLookup("CYP2B6", "*1", "*34");
    s_pharmcatAllMatches.testLookup("CYP2B6", "*33", "*36");

    s_pharmcatAllMatches.testMatchedGroups("efavirenz", 2);

    // test to make sure the two different phenotypes are present
    DrugReport efavirenze = s_pharmcatAllMatches.getContext().getDrugReport("efavirenz");
    Set<String> phenotypes = efavirenze.getMatchingRecommendations().stream()
        .map((r) -> r.getPhenotypes().get("CYP2B6"))
        .collect(Collectors.toSet());
    assertEquals(2, phenotypes.size());
    assertTrue(phenotypes.contains("Indeterminate"));
    assertTrue(phenotypes.contains("Intermediate Metabolizer"));
  }


  @Test
  void testCombined() throws Exception {
    s_pharmcatTopMatch.execute("test.combined", new String[]{
            "DPYD/s1s1.vcf",
            "UGT1A1/s1s1.vcf",
            "TPMT/s1s1.vcf",
            "cyp3a5/s1s7.vcf",
            "cftr/refref.vcf",
            "cyp2c19/s2s2.vcf",
            "cyp2c9/s2s3.vcf",
            "SLCO1B1/s1as1a.vcf",
            "VKORC1/-1639A-1639A.vcf",
            "cyp4f2/s1s1.vcf",
            "IFNL3/rs12979860CC.vcf"
        },
        s_outsideCallFilePath);

    s_pharmcatTopMatch.testCalledByMatcher("DPYD", "UGT1A1", "TPMT", "CYP3A5", "CFTR", "CYP2C19",
        "CYP2C9", "SLCO1B1", "VKORC1", "CYP4F2", "IFNL3", "CYP2D6");
    s_pharmcatTopMatch.testPrintCalls("TPMT", "*1/*1");
    s_pharmcatTopMatch.testPrintCalls("DPYD", "Reference/Reference");
    s_pharmcatTopMatch.testPrintCalls("CYP2C19", "*2/*2");
    s_pharmcatTopMatch.testLookup("TPMT", "*1", "*1");
    s_pharmcatTopMatch.testPrintCalls("CYP2D6", "*1/*4");
    s_pharmcatTopMatch.testPrintCalls("UGT1A1", "*1/*1");
  }

  /**
   * This tests the case when an outside call file contains two references to the same gene
   * supplied. This should result in an error
   */
  @Test
  void testBadOutsideData() throws Exception {
    Path badOutsideDataPath = Files.createTempFile("outsideCall", ".tsv");
    try (FileWriter fw = new FileWriter(badOutsideDataPath.toFile())) {
      fw.write("CYP2D6\t*1/*2\nCYP2D6\t*3/*4");
    }

    try {
      s_pharmcatTopMatch.execute("test.badOutsideData", new String[]{
          "cyp2c19/s2s2.vcf",
          "cyp2c9/s2s3.vcf",
      }, badOutsideDataPath);
      fail("Should have failed due to a duplicate gene definition in outside call");
    }
    catch (ParseException ex) {
      // we want this to fail so ignore handling the exception
    }
  }

  /**
   * This tests the case when the incoming sample file has coverage for a gene but then an outside call is also
   * supplied. This should result in an error
   */
  @Test
  void testCallerCollision() throws Exception {
    Path outsidePath = Files.createTempFile("outsideCall", ".tsv");
    try (FileWriter fw = new FileWriter(outsidePath.toFile())) {
      fw.write("CYP2C19\t*2/*2");
    }

    try {
      s_pharmcatTopMatch.execute("test.badOutsideData", new String[]{
          "cyp2c19/s2s2.vcf",
      }, outsidePath);
      fail("Should have failed due to a duplicate gene definition between matcher and outside caller");
    }
    catch (ParseException ex) {
      // we want this to fail so ignore handling the exception
    }
  }


  private static String printDiagnostic(GeneReport geneReport) {
    return String.format(
        sf_diplotypesTemplate,
        geneReport.getMatcherDiplotypes().toString(),
        geneReport.getReporterDiplotypes().toString(),
        geneReport.printDisplayCalls()
    );
  }

  
  private static class PharmCATTestWrapper {
    private final PharmCAT f_pharmCat;

    PharmCATTestWrapper(Path dirPath, boolean allMatches) throws IOException {
      f_pharmCat = new PharmCAT(dirPath, null);
      if (allMatches) {
        f_pharmCat.showAllMatches();
      }
    }

    /**
     * Runs the PharmCAT tool for the given example gene call data
     */
    void execute(String name, String[] geneCalls, Path outsideCallPath) throws Exception {
      Path tempVcfPath = Files.createTempFile(name, ".vcf");
      try (FileWriter fw = new FileWriter(tempVcfPath.toFile())) {
        fw.write(VcfTestUtils.writeVcf(geneCalls));
      } catch (Exception ex) {
        ex.printStackTrace();
        throw ex;
      }
      execute(tempVcfPath, outsideCallPath);
    }
    
    ReportContext getContext() {
      return f_pharmCat.getReporter().getContext();
    }
    
    void execute(Path vcfPath, Path outsidePath) throws Exception {
      f_pharmCat.execute(vcfPath, outsidePath, null);
    }

    /**
     * Test the "print" calls for a gene that will display in the final report or in the phenotyper. This will check that
     * the call count matches and then check each individual call is present (can be 1 or more).
     * @param gene the gene to get diplotypes for
     * @param calls the expected display of the calls, 1 or more
     */
    private void testPrintCalls(String gene, String... calls) {
      GeneReport geneReport = getContext().getGeneReport(gene);
      Collection<String> dips = geneReport.printDisplayCalls();
      assertEquals(calls.length, dips.size(), "Expected " + gene + " call count (" + calls.length + ") doesn't match actual call count (" + dips.size() + "): " + String.join(", ", dips));
      Arrays.stream(calls)
          .forEach(c -> assertTrue(dips.contains(c),
              c + " not in " + gene + ":" + dips + printDiagnostic(geneReport)));
    }

    /**
     * Test the diplotype that will be used for looking up the recommendation. This will mostly match what's printed in
     * displays but will differ for particular genes
     * @param gene the gene to get diplotypes for
     * @param haplotypes the expected haplotypes names used for calling, specifying one will assume homozygous, otherwise specify two haplotype names
     */
    private void testLookup(String gene, String... haplotypes) {
      Map<String,Integer> lookup = new HashMap<>();
      if (haplotypes.length == 1) {
        lookup.put(haplotypes[0], 2);
      } else if (haplotypes.length == 2) {
        if (haplotypes[0].equals(haplotypes[1])) {
          lookup.put(haplotypes[0], 2);
        } else {
          lookup.put(haplotypes[0], 1);
          lookup.put(haplotypes[1], 1);
        }
      } else {
        fail("Can only test on 1 or 2 haplotypes");
      }

      GeneReport geneReport = getContext().getGeneReport(gene);
      assertTrue(geneReport.isReportable());
      assertTrue(geneReport.getReporterDiplotypes().stream()
          .anyMatch(d -> d.makeLookupMap().equals(lookup)));
    }

    /**
     * Check to see if all the given genes have been called by the matcher
     */
    private void testCalledByMatcher(String... genes) {
      assertTrue(genes != null && genes.length > 0);

      Arrays.stream(genes)
          .forEach(g -> assertTrue(getContext().getGeneReport(g).isCalled(), g + " is not called"));
    }

    /**
     * Check to see if none of the given genes have been called by the matcher
     */
    private void testNotCalledByMatcher(String... genes) {
      assertTrue(genes != null && genes.length > 0);

      Arrays.stream(genes)
          .forEach(g -> assertFalse(getContext().getGeneReport(g).isCalled(), g + " is called"));
    }

    /**
     * Check to see if there is a matching recommendation for the given drug name
     * @param drugName a drug name that has recommendations
     * @param expectedCount the number of matching recommendations you expect
     */
    private void testMatchedGroups(String drugName, int expectedCount) {
      DrugReport guideline = getContext().getDrugReport(drugName);
      assertEquals(expectedCount, guideline.getMatchingRecommendations().size(),
          drugName + " does not have matching recommendation count of " + expectedCount);
    }
  }
}
