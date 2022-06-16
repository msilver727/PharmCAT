package org.pharmgkb.pharmcat.haplotype.model;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import com.google.common.base.Splitter;
import org.apache.commons.lang3.ObjectUtils;
import org.pharmgkb.pharmcat.definition.model.NamedAllele;
import org.pharmgkb.pharmcat.definition.model.VariantLocus;
import org.pharmgkb.pharmcat.haplotype.MatchData;


/**
 * This class represents a combination {@link NamedAllele} match.
 *
 * @author Mark Woon
 */
public class CombinationMatch extends BaseMatch {
  public static final String COMBINATION_JOINER = " + ";
  public static final Splitter COMBINATION_NAME_SPLITTER = Splitter.on(COMBINATION_JOINER).trimResults();
  private final SortedSet<NamedAllele> m_componentHaplotypes = new TreeSet<>();
  private final VariantLocus[] m_refVariants;


  public CombinationMatch(VariantLocus[] refVariants, NamedAllele namedAllele, String sequence) {
    m_refVariants = refVariants;
    m_componentHaplotypes.add(namedAllele);
    setName(buildName());
    setHaplotype(buildHaplotype(0, false));
    addSequence(sequence);
  }

  public CombinationMatch(CombinationMatch combinationMatch) {
    m_refVariants = combinationMatch.getRefVariants();
    m_componentHaplotypes.addAll(combinationMatch.getComponentHaplotypes());
    setName(buildName());
    setHaplotype(buildHaplotype(0, false));
    addSequence(combinationMatch.getSequences().first());
  }

  /**
   * Constructor for {@link CombinationMatch} based on off-reference partial sequence.
   * <p>
   * This automatically <b>sets the score to 0</b>.  So you should NOT call
   * {@link BaseMatch#finalizeCombinationHaplotype(MatchData)} on this.
   */
  public CombinationMatch(MatchData matchData, NamedAllele reference, String seq) {
    m_refVariants = matchData.getPositions();
    m_componentHaplotypes.add(reference);
    StringBuilder builder = new StringBuilder();
    int numPartials = 0;
    for (int x = 0; x < matchData.getPositions().length; x += 1) {
      String allele = matchData.getAllele(seq, x);
      if (!reference.getAlleles()[x].equals(allele)) {
        VariantLocus vl = matchData.getPositions()[x];
        if (builder.length() > 0) {
          builder.append(" + ");
        }
        builder.append(vl.getHgvsForVcfAllele(allele));
        numPartials += 1;
      }
    }
    setName(builder.toString());
    setHaplotype(buildHaplotype(numPartials, true));
    addSequence(seq);
  }

  public int getNumCombinations() {
    return m_componentHaplotypes.size();
  }

  private String buildName() {
    return m_componentHaplotypes.stream()
        .map(NamedAllele::getName)
        .collect(Collectors.joining(COMBINATION_JOINER));
  }

  /**
   * Builds a new {@link NamedAllele} based on component haplotypes.
   *
   * @param numPartials number of partials in this new {@link NamedAllele}
   * @param isOffReferencePartial if true, will set score to 0
   */
  private NamedAllele buildHaplotype(int numPartials, boolean isOffReferencePartial) {
    if (m_componentHaplotypes.first().getAlleles().length != m_componentHaplotypes.first().getCpicAlleles().length) {
      throw new IllegalStateException(m_componentHaplotypes.first() + " has different number of alleles and cpicAlleles");
    }
    StringBuilder idBuilder = new StringBuilder();
    SortedSet<VariantLocus> missingPositions = new TreeSet<>();
    for (NamedAllele na : m_componentHaplotypes) {
      if (idBuilder.length() > 0) {
        idBuilder.append(COMBINATION_JOINER);
      }
      idBuilder.append(na.getId());
      missingPositions.addAll(na.getMissingPositions());
    }
    String[] alleles = new String[m_componentHaplotypes.first().getAlleles().length];
    String[] cpicAlleles = new String[alleles.length];
    for (int x = 0; x < alleles.length; x += 1) {
      for (NamedAllele na : m_componentHaplotypes) {
        if (alleles[x] == null) {
          alleles[x] = na.getAlleles()[x];
        } else if (na.getAlleles()[x] != null && !alleles[x].equals(na.getAlleles()[x])) {
          throw new IllegalStateException(getName() + " has different alleles @ index " + x);
        }
        if (cpicAlleles[x] == null) {
          cpicAlleles[x] = na.getCpicAlleles()[x];
        } else if (na.getCpicAlleles()[x] != null && !cpicAlleles[x].equals(na.getCpicAlleles()[x])) {
          throw new IllegalStateException(getName() + " has different CPIC alleles @ index " + x);
        }
      }
    }
    NamedAllele na = new NamedAllele(idBuilder.toString(), getName(), alleles, cpicAlleles, missingPositions,
        false, m_componentHaplotypes.size(), numPartials);
    if (isOffReferencePartial) {
      na.initialize(m_refVariants, 0);
    } else {
      na.initialize(m_refVariants);
    }
    return na;
  }


  SortedSet<NamedAllele> getComponentHaplotypes() {
    return m_componentHaplotypes;
  }

  private VariantLocus[] getRefVariants() {
    return m_refVariants;
  }


  public boolean canMerge(NamedAllele namedAllele, String seq) {
    if (getSequences().first().equals(seq)) {
      for (NamedAllele na : m_componentHaplotypes) {
        for (int x = 0; x < namedAllele.getAlleles().length; x += 1) {
          // haplotypes cannot have overlapping variations (no sharing!)
          if (namedAllele.getAlleles()[x] != null && na.getAlleles()[x] != null) {
            return false;
          }
        }
      }
      return true;
    }
    return false;
  }

  public void merge(NamedAllele namedAllele) {
    m_componentHaplotypes.add(namedAllele);
    setName(buildName());
    setHaplotype(buildHaplotype(0, false));
  }


  @Override
  public int compareTo(BaseMatch o) {
    if (this == o) {
      return 0;
    }
    if (o instanceof CombinationMatch cm) {
      Iterator<NamedAllele> it1 = m_componentHaplotypes.iterator();
      Iterator<NamedAllele> it2 = cm.getComponentHaplotypes().iterator();
      while (it1.hasNext()) {
        NamedAllele na1 = it1.next();
        if (it2.hasNext()) {
          NamedAllele na2 = it2.next();
          int rez = ObjectUtils.compare(na1, na2);
          if (rez != 0) {
            return rez;
          }
        } else {
          return 1;
        }
      }
      if (it2.hasNext()) {
        return -1;
      }
      return compareSequences(o);
    }
    if (o instanceof HaplotypeMatch hm) {
      if (getName().startsWith("g.")) {
        // push off-reference partial to bottom
        return 1;
      }
      int rez = ObjectUtils.compare(m_componentHaplotypes.first(), hm.getHaplotype());
      if (rez != 0) {
        return rez;
      }
      return 1;
    }
    return ObjectUtils.compare(getName(), o.getName());
  }

  @Override
  public int hashCode() {
    return Objects.hash(getName(), Arrays.hashCode(getSequences().toArray()));
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof CombinationMatch that)) {
      return false;
    }
    return Objects.equals(getName(), that.getName()) &&
        Arrays.equals(getSequences().toArray(), that.getSequences().toArray());
  }
}