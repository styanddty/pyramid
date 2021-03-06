package edu.neu.ccs.pyramid.multilabel_classification.cbm;

import edu.neu.ccs.pyramid.classification.Classifier;
import edu.neu.ccs.pyramid.classification.lkboost.LKBOutputCalculator;
import edu.neu.ccs.pyramid.classification.lkboost.LKBoost;
import edu.neu.ccs.pyramid.classification.lkboost.LKBoostOptimizer;
import edu.neu.ccs.pyramid.classification.logistic_regression.ElasticNetLogisticTrainer;
import edu.neu.ccs.pyramid.classification.logistic_regression.LogisticLoss;
import edu.neu.ccs.pyramid.classification.logistic_regression.LogisticRegression;
import edu.neu.ccs.pyramid.classification.logistic_regression.RidgeLogisticOptimizer;
import edu.neu.ccs.pyramid.dataset.DataSetUtil;
import edu.neu.ccs.pyramid.dataset.MultiLabel;
import edu.neu.ccs.pyramid.dataset.MultiLabelClfDataSet;
import edu.neu.ccs.pyramid.eval.Entropy;
import edu.neu.ccs.pyramid.eval.KLDivergence;
import edu.neu.ccs.pyramid.multilabel_classification.MLScorer;
import edu.neu.ccs.pyramid.optimization.Terminator;
import edu.neu.ccs.pyramid.regression.regression_tree.RegTreeConfig;
import edu.neu.ccs.pyramid.regression.regression_tree.RegTreeFactory;
import edu.neu.ccs.pyramid.util.MathUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.mahout.math.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Created by chengli on 10/4/16.
 */
public class CBMUtilityOptimizer {
    private static final Logger logger = LogManager.getLogger();
    private CBM cbm;
    private MultiLabelClfDataSet dataSet;
    private Terminator terminator;

    // format [#data][#components]
    double[][] gammas;
    // format [#components][#data]
    double[][] gammasT;

    // format [#labels][#data][2]
    // to be fit by binary classifiers
    private double[][][] binaryTargetsDistributions;

    // lr parameters
    // regularization for multiClassClassifier
    private double priorVarianceMultiClass =1;
    // regularization for binary logisticRegression
    private double priorVarianceBinary =1;

    // elasticnet parameters
    private double regularizationMultiClass = 1.0;
    private double regularizationBinary = 1.0;
    private double l1RatioBinary = 0.0;
    private double l1RatioMultiClass = 0.0;
    private boolean lineSearch = true;


    // boosting parameters
    private int numLeavesBinary = 2;
    private int numLeavesMultiClass = 2;
    private double shrinkageBinary = 0.1;
    private double shrinkageMultiClass = 0.1;
    private int numIterationsBinary = 20;
    private int numIterationsMultiClass = 20;

    private List<MultiLabel> combinations;

    // size = [num data][num combination]
    private double[][] targets;
    // size = [num data][num combination]
    private double[][] probabilities;
    // size = [num data][num combination]
    private double[][] scores;

    public CBMUtilityOptimizer(CBM cbm, MultiLabelClfDataSet dataSet, MLScorer mlScorer) {
        this.cbm = cbm;
        this.dataSet = dataSet;
        this.combinations = DataSetUtil.gatherMultiLabels(dataSet);
        this.terminator = new Terminator();
        this.terminator.setGoal(Terminator.Goal.MINIMIZE);

        this.gammas = new double[dataSet.getNumDataPoints()][cbm.getNumComponents()];
        this.gammasT = new double[cbm.getNumComponents()][dataSet.getNumDataPoints()];

        this.binaryTargetsDistributions = new double[cbm.getNumClasses()][dataSet.getNumDataPoints()][2];
        this.scores = new double[dataSet.getNumDataPoints()][combinations.size()];
        for (int i=0;i<dataSet.getNumDataPoints();i++){
            for (int j=0;j<combinations.size();j++){
                MultiLabel truth = dataSet.getMultiLabels()[i];
                MultiLabel combination = combinations.get(j);
                double f = mlScorer.score(dataSet.getNumClasses(),truth,combination);
                scores[i][j] = f;
            }
        }
        this.targets = new double[dataSet.getNumDataPoints()][combinations.size()];
        this.probabilities = new double[dataSet.getNumDataPoints()][combinations.size()];
        this.updateProbabilities();
        if (logger.isDebugEnabled()){
            logger.debug("finish constructor");
        }
    }

    private void updateProbabilities(int dataPointIndex){
        probabilities[dataPointIndex] = cbm.predictAssignmentProbs(dataSet.getRow(dataPointIndex), combinations);
    }

    private void updateProbabilities(){
        if (logger.isDebugEnabled()){
            logger.debug("start updateProbabilities()");
        }
        IntStream.range(0, dataSet.getNumDataPoints()).parallel().forEach(this::updateProbabilities);
        if (logger.isDebugEnabled()){
            logger.debug("finish updateProbabilities()");
        }

//        // todo check probabilities
//        for (int i=0;i<dataSet.getNumDataPoints();i++){
//            for (int c=0;c<cbm.numComponents;c++){
//                if (probabilities[i][c]<0){
//                    throw new RuntimeException("probability = "+probabilities[i][c]);
//                }
//            }
//        }
    }



    private  void updateTargets(int dataPointIndex){
        double[] probs = probabilities[dataPointIndex];
        double[] product = new double[probs.length];
        double[] s = this.scores[dataPointIndex];
        for (int j=0;j<probs.length;j++){
            product[j] = probs[j]*s[j];
        }

        double denominator = MathUtil.arraySum(product);
        for (int j=0;j<probs.length;j++){
            targets[dataPointIndex][j] = product[j]/denominator;
        }
    }


    private void updateTargets(){
        if (logger.isDebugEnabled()){
            logger.debug("start updateTargets()");
        }
        IntStream.range(0, dataSet.getNumDataPoints()).parallel().forEach(this::updateTargets);
        if (logger.isDebugEnabled()){
            logger.debug("finish updateTargets()");
        }
    }


    private void updateBinaryTargets(){
        if (logger.isDebugEnabled()){
            logger.debug("start updateBinaryTargets()");
        }
        IntStream.range(0, dataSet.getNumDataPoints()).parallel()
                .forEach(this::updateBinaryTarget);
        if (logger.isDebugEnabled()){
            logger.debug("finish updateBinaryTargets()");
        }
//        System.out.println(Arrays.deepToString(binaryTargetsDistributions));
    }

    private void updateBinaryTarget(int dataPointIndex){
        double[] comProb = targets[dataPointIndex];
        double[] marginals = new double[cbm.getNumClasses()];
        for (int c=0;c<comProb.length;c++){
            MultiLabel multiLabel = combinations.get(c);
            double prob = comProb[c];
            for (int l: multiLabel.getMatchedLabels()){
                marginals[l] += prob;
            }
        }
        for (int l=0;l<cbm.getNumClasses();l++){
            // the sum may exceed 1 due to numerical issues
            // when that happens, the probability of the negative class would be negative
            // we need to add some protection
            if (marginals[l]>1){
                marginals[l]=1;
            }
            binaryTargetsDistributions[l][dataPointIndex][0] = 1-marginals[l];
            binaryTargetsDistributions[l][dataPointIndex][1] = marginals[l];
        }
    }



    public void setPriorVarianceMultiClass(double priorVarianceMultiClass) {
        this.priorVarianceMultiClass = priorVarianceMultiClass;
    }

    public void setPriorVarianceBinary(double priorVarianceBinary) {
        this.priorVarianceBinary = priorVarianceBinary;
    }

    public void setNumLeavesBinary(int numLeavesBinary) {
        this.numLeavesBinary = numLeavesBinary;
    }

    public void setNumLeavesMultiClass(int numLeavesMultiClass) {
        this.numLeavesMultiClass = numLeavesMultiClass;
    }

    public void setShrinkageBinary(double shrinkageBinary) {
        this.shrinkageBinary = shrinkageBinary;
    }

    public void setShrinkageMultiClass(double shrinkageMultiClass) {
        this.shrinkageMultiClass = shrinkageMultiClass;
    }

    public void setNumIterationsBinary(int numIterationsBinary) {
        this.numIterationsBinary = numIterationsBinary;
    }

    public void setNumIterationsMultiClass(int numIterationsMultiClass) {
        this.numIterationsMultiClass = numIterationsMultiClass;
    }

    public void optimize() {
        while (true) {
            iterate();
            if (terminator.shouldTerminate()) {
                break;
            }
        }
    }

    public void iterate() {
        updateTargets();
        updateBinaryTargets();
        updateGamma();
        updateMultiClassClassifier();
        updateBinaryClassifiers();
        updateProbabilities();
        this.terminator.add(objective());
    }




    private void updateGamma() {
        if (logger.isDebugEnabled()){
            logger.debug("start updateGamma()");
        }
        IntStream.range(0, dataSet.getNumDataPoints()).parallel()
                .forEach(this::updateGamma);
        if (logger.isDebugEnabled()){
            logger.debug("finish updateGamma()");
        }
//        System.out.println("gamma="+Arrays.deepToString(gammas));
    }


    private void updateGamma(int n) {
        Vector x = dataSet.getRow(n);
        BMDistribution bmDistribution = cbm.computeBM(x);
        // size = combination * components
        List<double[]> logPosteriors = new ArrayList<>();
        for (int c=0;c<combinations.size();c++){
            MultiLabel combination = combinations.get(c);
            double[] pos = bmDistribution.logPosteriorMembership(combination);
            logPosteriors.add(pos);
        }

        double[] sums = new double[cbm.numComponents];
        for (int k=0;k<cbm.numComponents;k++){
            double sum = 0;
            for (int c=0;c<combinations.size();c++){
                sum += targets[n][c]*logPosteriors.get(c)[k];
            }
            sums[k] = sum;
        }
        double[] posterior = MathUtil.softmax(sums);
        for (int k=0; k<cbm.numComponents; k++) {
            gammas[n][k] = posterior[k];
            gammasT[k][n] = posterior[k];
        }
    }


    private void updateBinaryClassifiers() {
        if (logger.isDebugEnabled()){
            logger.debug("start updateBinaryClassifiers()");
        }
        IntStream.range(0, cbm.numComponents).forEach(this::updateBinaryClassifiers);
        if (logger.isDebugEnabled()){
            logger.debug("finish updateBinaryClassifiers()");
        }
    }

    //todo pay attention to parallelism
    private void updateBinaryClassifiers(int component){
        String type = cbm.getBinaryClassifierType();
        switch (type){
            case "lr":
                IntStream.range(0, cbm.numLabels).parallel().forEach(l-> updateBinaryLogisticRegression(component,l));
                break;
            case "boost":
                // no parallel for boosting
                IntStream.range(0, cbm.numLabels).forEach(l -> updateBinaryBoosting(component, l));
                break;
            case "elasticnet":
                IntStream.range(0, cbm.numLabels).parallel().forEach(l-> updateBinaryLogisticRegressionEL(component,l));
                break;
            default:
                throw new IllegalArgumentException("unknown type: " + cbm.getBinaryClassifierType());
        }
    }

    private void updateBinaryBoosting(int componentIndex, int labelIndex){
        int numIterations = numIterationsBinary;
        double shrinkage = shrinkageBinary;
        LKBoost boost = (LKBoost)this.cbm.binaryClassifiers[componentIndex][labelIndex];
        RegTreeConfig regTreeConfig = new RegTreeConfig()
                .setMaxNumLeaves(numLeavesBinary);
        RegTreeFactory regTreeFactory = new RegTreeFactory(regTreeConfig);
        regTreeFactory.setLeafOutputCalculator(new LKBOutputCalculator(2));
        LKBoostOptimizer optimizer = new LKBoostOptimizer(boost,dataSet, regTreeFactory,
                gammasT[componentIndex], binaryTargetsDistributions[labelIndex]);
        optimizer.setShrinkage(shrinkage);
        optimizer.initialize();
        optimizer.iterate(numIterations);
    }

    private void updateBinaryLogisticRegression(int componentIndex, int labelIndex){
        RidgeLogisticOptimizer ridgeLogisticOptimizer;
//        System.out.println("for component "+componentIndex+"  label "+labelIndex);
//        System.out.println("weights="+Arrays.toString(gammasT[componentIndex]));
//        System.out.println("binary target distribution="+Arrays.deepToString(binaryTargetsDistributions[labelIndex]));
//        double posProb = 0;
//        double negProb = 0;
//        for (int i=0;i<dataSet.getNumDataPoints();i++){
//            posProb += gammasT[componentIndex][i] * binaryTargetsDistributions[labelIndex][i][1];
//            negProb += gammasT[componentIndex][i] * binaryTargetsDistributions[labelIndex][i][0];
//        }
//        System.out.println("sum pos prob = "+posProb);
//        System.out.println("sum neg prob = "+negProb);
        // no parallelism
        ridgeLogisticOptimizer = new RidgeLogisticOptimizer((LogisticRegression)cbm.binaryClassifiers[componentIndex][labelIndex],
                dataSet, gammasT[componentIndex], binaryTargetsDistributions[labelIndex], priorVarianceBinary, false);
        //TODO maximum iterations
        ridgeLogisticOptimizer.getOptimizer().getTerminator().setMaxIteration(10);
        ridgeLogisticOptimizer.optimize();
//        if (logger.isDebugEnabled()){
//            logger.debug("for cluster "+clusterIndex+" label "+labelIndex+" history= "+ridgeLogisticOptimizer.getOptimizer().getTerminator().getHistory());
//        }
    }

    private void updateBinaryLogisticRegressionEL(int componentIndex, int labelIndex) {
        ElasticNetLogisticTrainer elasticNetLogisticTrainer = new ElasticNetLogisticTrainer.Builder((LogisticRegression)
                cbm.binaryClassifiers[componentIndex][labelIndex], dataSet, 2, binaryTargetsDistributions[labelIndex], gammasT[componentIndex])
                .setRegularization(regularizationBinary)
                .setL1Ratio(l1RatioBinary)
                .setLineSearch(lineSearch).build();
        //TODO: maximum iterations
        elasticNetLogisticTrainer.getTerminator().setMaxIteration(10);
        elasticNetLogisticTrainer.optimize();
    }

    private void updateMultiClassClassifier(){
        if (logger.isDebugEnabled()){
            logger.debug("start updateMultiClassClassifier()");
        }
        String type = cbm.getMultiClassClassifierType();
        switch (type){
            case "lr":
                updateMultiClassLR();
                break;
            case "boost":
                updateMultiClassBoost();
                break;
            case "elasticnet":
                updateMultiClassEL();
                break;
            default:
                throw new IllegalArgumentException("unknown type: " + cbm.getMultiClassClassifierType());
        }
        if (logger.isDebugEnabled()){
            logger.debug("finish updateMultiClassClassifier()");
        }
    }

    private void updateMultiClassEL() {
        ElasticNetLogisticTrainer elasticNetLogisticTrainer = new ElasticNetLogisticTrainer.Builder((LogisticRegression)
                cbm.multiClassClassifier, dataSet, cbm.multiClassClassifier.getNumClasses(), gammas)
                .setRegularization(regularizationMultiClass)
                .setL1Ratio(l1RatioMultiClass)
                .setLineSearch(lineSearch).build();
        // TODO: maximum iterations
        elasticNetLogisticTrainer.getTerminator().setMaxIteration(10);
        elasticNetLogisticTrainer.optimize();
    }

    private void updateMultiClassLR() {
        // parallel
        RidgeLogisticOptimizer ridgeLogisticOptimizer = new RidgeLogisticOptimizer((LogisticRegression)cbm.multiClassClassifier,
                dataSet, gammas, priorVarianceMultiClass, true);
        //TODO maximum iterations
        ridgeLogisticOptimizer.getOptimizer().getTerminator().setMaxIteration(10);
        ridgeLogisticOptimizer.optimize();
    }

    private void updateMultiClassBoost() {
        int numComponents = cbm.numComponents;
        int numIterations = numIterationsMultiClass;
        double shrinkage = shrinkageMultiClass;
        LKBoost boost = (LKBoost)this.cbm.multiClassClassifier;
        RegTreeConfig regTreeConfig = new RegTreeConfig()
                .setMaxNumLeaves(numLeavesMultiClass);
        RegTreeFactory regTreeFactory = new RegTreeFactory(regTreeConfig);
        regTreeFactory.setLeafOutputCalculator(new LKBOutputCalculator(numComponents));

        LKBoostOptimizer optimizer = new LKBoostOptimizer(boost, dataSet, regTreeFactory, gammas);
        optimizer.setShrinkage(shrinkage);
        optimizer.initialize();
        optimizer.iterate(numIterations);
    }

//    public static Object[] getColumn(Object[][] array, int index){
//        Object[] column = new Object[array[0].length]; // Here I assume a rectangular 2D array!
//        for(int i=0; i<column.length; i++){
//            column[i] = array[i][index];
//        }
//        return column;
//    }



    private double objective(int dataPointIndex){
        double sum = 0;
        double[] p = probabilities[dataPointIndex];
        double[] s = scores[dataPointIndex];
        for (int j=0;j<p.length;j++){
            sum += p[j]*s[j];
        }
        return -Math.log(sum);
    }

    public double objective(){
        if (logger.isDebugEnabled()){
            logger.debug("start objective()");
        }
        double obj= IntStream.range(0, dataSet.getNumDataPoints()).parallel()
                .mapToDouble(this::objective).sum();
        if (logger.isDebugEnabled()){
            logger.debug("finish obj");
        }
        double penalty =  penalty();
        if (logger.isDebugEnabled()){
            logger.debug("finish penalty");
        }
        if (logger.isDebugEnabled()){
            logger.debug("finish objective()");
        }
        return obj+penalty;
    }

    // regularization
    private double penalty(){
        double sum = 0;
        LogisticLoss logisticLoss =  new LogisticLoss((LogisticRegression) cbm.multiClassClassifier,
                dataSet, gammas, priorVarianceMultiClass, true);
        sum += logisticLoss.penaltyValue();
        for (int k=0;k<cbm.numComponents;k++){
            for (int l=0;l<cbm.getNumClasses();l++){
                sum += new LogisticLoss((LogisticRegression) cbm.binaryClassifiers[k][l],
                        dataSet, gammasT[k], binaryTargetsDistributions[l], priorVarianceBinary, true).penaltyValue();
            }
        }
        return sum;
    }

//    //TODO: use direct obj
//    public double getObjective() {
//        return multiClassClassifierObj() + binaryObj() +(1-temperature)*getEntropy();
//    }

//    private double getMStepObjective() {
//        KLLogisticLoss logisticLoss =  new KLLogisticLoss(bmmClassifier.multiClassClassifier,
//                dataSet, gammas, priorVarianceMultiClass);
//        // Q function for \Thata + gamma.entropy and Q function for Weights
//        return logisticLoss.getValue() + binaryLRObj();
//    }

    private double getEntropy() {
        return IntStream.range(0, dataSet.getNumDataPoints()).parallel()
                .mapToDouble(this::getEntropy).sum();
    }

    private double getEntropy(int i) {
        return Entropy.entropy(gammas[i]);
    }


    private double binaryObj(){
        return IntStream.range(0, cbm.numComponents).mapToDouble(this::binaryObj).sum();
    }

    private double binaryObj(int clusterIndex){
        return IntStream.range(0, cbm.numLabels).parallel().mapToDouble(l->binaryObj(clusterIndex,l)).sum();
    }

    private double binaryObj(int clusterIndex, int classIndex){
        String type = cbm.getBinaryClassifierType();
        switch (type){
            case "lr":
                return binaryLRObj(clusterIndex, classIndex);
            case "boost":
                return binaryBoostObj(clusterIndex, classIndex);
            case "elasticnet":
                // todo
                return binaryLRObj(clusterIndex, classIndex);
            default:
                throw new IllegalArgumentException("unknown type: " + type);
        }
    }

    // consider regularization penalty
    private double binaryLRObj(int clusterIndex, int classIndex) {
        LogisticLoss logisticLoss = new LogisticLoss((LogisticRegression) cbm.binaryClassifiers[clusterIndex][classIndex],
                dataSet, gammasT[clusterIndex], binaryTargetsDistributions[classIndex], priorVarianceBinary, false);
        return logisticLoss.getValue();
    }

    private double binaryBoostObj(int clusterIndex, int classIndex){
        Classifier.ProbabilityEstimator estimator = cbm.binaryClassifiers[clusterIndex][classIndex];
        double[][] targets = binaryTargetsDistributions[classIndex];
        double[] weights = gammasT[clusterIndex];
        return KLDivergence.kl(estimator, dataSet, targets, weights);
    }

    private double multiClassClassifierObj(){
        String type = cbm.getMultiClassClassifierType();
        switch (type){
            case "lr":
                return multiClassLRObj();
            case "boost":
                return multiClassBoostObj();
            //TODO: change to elastic net
            case "elasticnet":
                return multiClassLRObj();
            default:
                throw new IllegalArgumentException("unknown type: " + type);
        }
    }

    private double multiClassBoostObj(){
        Classifier.ProbabilityEstimator estimator = cbm.multiClassClassifier;
        double[][] targets = gammas;
        return KLDivergence.kl(estimator,dataSet,targets);
    }

    private double multiClassLRObj(){
        LogisticLoss logisticLoss =  new LogisticLoss((LogisticRegression) cbm.multiClassClassifier,
                dataSet, gammas, priorVarianceMultiClass, true);
        return logisticLoss.getValue();
    }


    public Terminator getTerminator() {
        return terminator;
    }

    public double[][] getGammas() {
        return gammas;
    }

    public double[][] getPIs() {
        double[][] PIs = new double[dataSet.getNumDataPoints()][cbm.getNumComponents()];

        for (int n=0; n<PIs.length; n++) {
            double[] logProbs = cbm.multiClassClassifier.predictLogClassProbs(dataSet.getRow(n));
            for (int k=0; k<PIs[n].length; k++) {
                PIs[n][k] = Math.exp(logProbs[k]);
            }
        }
        return PIs;
    }

    // For ElasticEet Parameters
    public double getRegularizationMultiClass() {
        return regularizationMultiClass;
    }

    public void setRegularizationMultiClass(double regularizationMultiClass) {
        this.regularizationMultiClass = regularizationMultiClass;
    }

    public double getRegularizationBinary() {
        return regularizationBinary;
    }

    public void setRegularizationBinary(double regularizationBinary) {
        this.regularizationBinary = regularizationBinary;
    }

    public boolean isLineSearch() {
        return lineSearch;
    }

    public void setLineSearch(boolean lineSearch) {
        this.lineSearch = lineSearch;
    }

    public double getL1RatioBinary() {
        return l1RatioBinary;
    }

    public void setL1RatioBinary(double l1RatioBinary) {
        this.l1RatioBinary = l1RatioBinary;
    }

    public double getL1RatioMultiClass() {
        return l1RatioMultiClass;
    }

    public void setL1RatioMultiClass(double l1RatioMultiClass) {
        this.l1RatioMultiClass = l1RatioMultiClass;
    }
}
