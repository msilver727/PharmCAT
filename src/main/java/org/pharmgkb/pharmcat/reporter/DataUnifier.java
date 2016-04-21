package org.pharmgkb.pharmcat.reporter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.pharmgkb.pharmcat.haplotype.model.json.DiplotypeCall;
import org.pharmgkb.pharmcat.reporter.model.CPICException;
import org.pharmgkb.pharmcat.reporter.model.CPICinteraction;
import org.pharmgkb.pharmcat.reporter.model.Group;
import org.pharmgkb.pharmcat.reporter.resultsJSON.Gene;
import org.pharmgkb.pharmcat.reporter.resultsJSON.Interaction;


public class DataUnifier {
    List<DiplotypeCall> calls;
    Map<String, List<CPICException>> exceptions;
    Map<String, List<CPICinteraction>> drugGenes;



   public DataUnifier( List<DiplotypeCall> calls,
                       Map<String, List<CPICException>> matches,
                       Map<String, List<CPICinteraction>> drugGenes){
        this.calls = calls;
        this.exceptions = matches;
        this.drugGenes = drugGenes;
    }

    public List<Gene> findMatches(){

        List<Gene> callSetToReturn = new ArrayList<Gene>();
        ExceptionMatcher exceptMatchTest = new ExceptionMatcher();
        DrugRecommendationMatcher recMatchTest = new DrugRecommendationMatcher();


        for( DiplotypeCall call : calls ){

            Gene gene = new Gene(call);

            if( exceptions.containsKey(call.getGene()) ){
                for( CPICException exception : exceptions.get(call.getGene() ) ){
                   if( exceptMatchTest.test( gene, exception.getMatches()) ){
                       gene.addException(exception);
                   }

                }

            }

            if( drugGenes.containsKey(call.getGene()) ){
                for( CPICinteraction interact : drugGenes.get( call.getGene()) ){
                    List<Group> recommend = recMatchTest.test( gene.getDips(), interact );                    Interaction act = new Interaction(interact);
                    act.addAllToGroup(recommend);
                    gene.addInteraction(act);
                 }

            }

            callSetToReturn.add(gene);

        }

        return callSetToReturn;
    }


}