/*
 * Here comes the text of your license
 * Each line should be prefixed with  * 
 */
package nars.predict;

import automenta.vivisect.TreeMLData;
import automenta.vivisect.swing.NSlider;
import automenta.vivisect.swing.NWindow;
import automenta.vivisect.swing.PCanvas;
import automenta.vivisect.timeline.LineChart;
import automenta.vivisect.timeline.TimelineVis;
import com.google.common.util.concurrent.AtomicDouble;
import java.awt.BorderLayout;
import static java.awt.BorderLayout.CENTER;
import static java.awt.BorderLayout.WEST;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import nars.core.Events.TaskImmediateProcess;
import nars.core.NAR;
import nars.core.Parameters;
import nars.core.build.Default;
import nars.core.control.NAL;
import nars.entity.Task;
import nars.gui.NARSwing;
import nars.inference.TruthFunctions;
import nars.io.ChangedTextInput;
import nars.io.narsese.Narsese;
import nars.language.Tense;
import nars.language.Term;

/**
 *
 * https://github.com/encog/encog-java-examples/blob/master/src/main/java/org/encog/examples/neural/predict/sunspot/PredictSunspotElman.java
 * https://github.com/encog/encog-java-examples/blob/master/src/main/java/org/encog/examples/neural/recurrent/elman/ElmanXOR.java
 *
 * @author me
 */
public class PredictGUI extends JPanel {
    
    final int maxDiscretization = 9; //because of the digit assumption

    final int minFutureTime = 5; //set to zero to consider beliefs about present time as a prediction; > 0 means all predictions must be some time ahead in the future
    
    float signal = 0;
    
    float predictionMagnitudeThreshold = 0.5f; //min magnitude of a prediction to be considered

    boolean projectFutureBeliefs = false;
    
    final int historySize = 128;
    private boolean allowNegativeBeliefs = true;
    
    
    int updatePeriodMS = 5;
    private final NAR n;
    private final GridBagConstraints cons;

    private final AtomicDouble noiseRate;
    private final AtomicDouble missingRate;
    private final AtomicDouble discretizationAtomic;
    private final AtomicDouble signalPeriod;
    private final AtomicDouble signalType;
    
    

    
    public int getSignalMode() {
        return signalType.intValue();
    }
    
    public int getDuration() {
        return 5;
    }
    
    public float getFrequency() {
        return (float)( 1.0 / signalPeriod.doubleValue() );
    }
    
    public int getDiscretization() {
        return discretizationAtomic.intValue();
    }
    
    public int getThinkInterval() {
        return 100;
    }
    
    public float getMissingRate() {
        return missingRate.floatValue();
    }
    
    public float getNoiseRate() {
        return noiseRate.floatValue();
    }
    
    private boolean allowsRepeatInputs() {
        return false;
    }
    private int getMaxConceptBeliefs() {
        return 64;
    }
    
    
    
    public double nextSignal(double t) {
        float f = getFrequency();
        switch (getSignalMode()) {
            case 0:
                /** sine wave */
                return Math.sin(f * t) * 0.5f + 0.5f;
            case 1:
                /** square wave */
                return (Math.sin(f * t) > 0 ? 1f : -1f) * 0.5f + 0.5f;
            case 2:
                //tan(x)
                return Math.max(0, Math.min(1.0, Math.tan(f * t) * 0.5f + 0.5f));
            case 3:
                //constant value
                return 0; 
            case 4:
                //random
                return Math.random();
            default:
                throw new RuntimeException("Unknown signal type");
        }
    }
    
    public PredictGUI() throws Narsese.InvalidInputException {
        super();
        
        setLayout(new GridBagLayout());
        cons = new GridBagConstraints();
        cons.fill = GridBagConstraints.HORIZONTAL;
        cons.weightx = 1;
        cons.gridx = 0;
        
        
        
        setMinimumSize(new Dimension(150, 500));
        setPreferredSize(new Dimension(150, 500));
        
        
        addSlider("Signal Type", signalType = new AtomicDouble(0), 0, 5f);
        addSlider("Signal Period", signalPeriod = new AtomicDouble(1000), 0, 2000f);
        addSlider("Noise Rate", noiseRate = new AtomicDouble(0), 0, 1.0f);
        addSlider("Missing Rate", missingRate = new AtomicDouble(0), 0, 1.0f);
        addSlider("Discretization", discretizationAtomic = new AtomicDouble(7), 2, maxDiscretization);
        
        Parameters.DEBUG = true;

        n = new NAR(new Default().setInternalExperience(null));
        n.param.duration.set(getDuration());
        //n.param.duration.setLinear(0.5);
        n.param.conceptBeliefsMax.set(getMaxConceptBeliefs());
        n.param.noiseLevel.set(0);
        //n.param.conceptForgetDurations.set(16);
        
        Discretize d = new Discretize(n, getDiscretization());
        
        TreeMLData observed = new TreeMLData("observed", Color.WHITE, historySize).setRange(0, 1f);
        TreeMLData observedDiscrete = new TreeMLData("observed", Color.WHITE, historySize);
        
        TreeMLData prediction = new TreeMLData("prediction", Color.WHITE, historySize).setRange(0, 1f).setDefaultValue(0);
        TreeMLData predictionGreedy = new TreeMLData("prediction", Color.WHITE, historySize).setRange(0, 1f);
        
        TreeMLData error = new TreeMLData("error", Color.WHITE, historySize).setRange(0, 1f);

        TreeMLData[] predictionsPos = new TreeMLData[maxDiscretization];
        TreeMLData[] predictionsNeg = new TreeMLData[maxDiscretization];
        TreeMLData[] reflections = new TreeMLData[maxDiscretization];
        
        for (int i = 0; i < predictionsPos.length; i++) {
            predictionsPos[i] = new TreeMLData("+Pred" + i,
                    Color.getHSBColor(0.25f + i / 4f, 0.85f, 0.85f), historySize);
            
            predictionsNeg[i] = new TreeMLData("-Pred" + i,
                    Color.getHSBColor(0.25f + i / 4f, 0.85f, 0.85f), historySize);

            reflections[i] = new TreeMLData("Refl" + i,
                    Color.getHSBColor(0.25f + i / 4f, 0.85f, 0.85f), historySize);
            reflections[i].setDefaultValue(0.0);
        }
        
        
        n.on(TaskImmediateProcess.class, new TaskImmediateProcess() {
            
            //int curmax=0;
            
            protected void updatePrediction(int t) {
                //weigh positive and negative predictions at time t to determine final aggregate prediction belief
                int strongest = -1;
                double strongestAmount = Double.NEGATIVE_INFINITY;
                
                int meanSamples = 0;
                double total = 0;
                
                for (int i = 0; i < getDiscretization(); i++) {
                    
                    double pos = predictionsPos[i].getData(t);
                    double neg = predictionsNeg[i].getData(t);
                    
                    if (Double.isNaN(pos) && Double.isNaN(neg)) {
                        
                        continue;
                    }
                    
                    double belief = 0;
                    
                    if (!Double.isNaN(pos)) belief += pos;
                    if (allowNegativeBeliefs)
                        if (!Double.isNaN(neg)) belief -= neg;
                    
                        
                    
                    
                    
                    if (belief > 0) {
                        
                        if (belief > strongestAmount) {
                            strongest = i;
                            strongestAmount = belief;
                        }
                        
                        total += i * belief;
                        meanSamples++;
                    }
                    
                }
                
                /*
                //winner take all mode:
                if (strongest!=-1) {
                    
                    prediction.setData(t, d.continuous(strongest));                    
                }
                else
                    prediction.setData(t, 0.5);
                */
                
                if (meanSamples > 0) {
                    double mean = total / meanSamples;
                    
                    prediction.setData(t, d.continuous(mean));
                    
                    predictionGreedy.setData(t, d.continuous(strongest));
                }
            }
            
            @Override
            public void onProcessed(Task t, NAL n) {
                if (!t.sentence.isJudgment()) return;
                if (t.sentence.isEternal()) return;
                
                float freq = t.sentence.truth.getFrequency();
                float conf = t.sentence.truth.getConfidence();
                boolean positive = freq >= 0.5;
                float magnitude = 2f * Math.abs( freq - 0.5f ) * conf;
                long now = n.memory.time();
                
                //float exp = t.sentence.truth.getExpectation();
                Term term = t.getTerm();
                String ts = term.toString();
                long then = t.sentence.getOccurenceTime();
                String prefix = "<{x} --> y";
                if (!ts.startsWith(prefix))
                    return;
                String suffix = ">";
                if (!ts.endsWith(suffix))
                    return;
                if (ts.length()!=prefix.length() + 1 + suffix.length())
                    return;
                    

                
                if ((then - now >= minFutureTime) && (magnitude >= predictionMagnitudeThreshold)) {
                     
                    char cc = ts.charAt(prefix.length());
                    int value = cc - '0';
                    if (value < 0) return;
                    if (value > 9) return;
                    TreeMLData pp = (positive) ? predictionsPos[value] : predictionsNeg[value];
                    
                    if (projectFutureBeliefs)
                        magnitude = TruthFunctions.temporalProjection(now, then, now);
                    
                    pp.max( (int)then, magnitude );
                
                    updatePrediction((int)then);
                }
                
                
            }
        });
        
        

        
        TimelineVis tc = new TimelineVis(
                new LineChart(observed).thickness(16f).height(128),               
                new LineChart(observedDiscrete).thickness(16f).height(128),               
                
                //new LineChart(predictionsPos).thickness(16f).height(128),
                new LineChart(prediction).thickness(16f).height(256),
                new LineChart(predictionGreedy).thickness(16f).height(256)
                //new LineChart(predictionsNeg).thickness(16f).height(128)
                
                //new BarChart(reflections).thickness(16f).height(128)
                /*new LineChart(predictions[1]).thickness(16f).height(128),
                new LineChart(predictions[2]).thickness(16f).height(128),*/
        );
        
        

        tc.camera.timeScale = 1f;
        
        JPanel sp = new JPanel(new BorderLayout());
        sp.add(new PCanvas(tc), CENTER);
        sp.add(new JScrollPane(this), WEST);
        
        new NWindow("_", sp).show(800, 800, true);

        //n.run((int)getDiscretization()*4); //initial thinking pause
        
        NARSwing.themeInvert();

        new NARSwing(n);
        
        ChangedTextInput chg=new ChangedTextInput(n);
        
        int val, lastVal=-1;
        
        while (true) {

            d.setDiscretization( getDiscretization() );
            n.param.duration.set(getDuration());
            chg.setAllowRepeatInputs(allowsRepeatInputs());
            
            
            n.run(getThinkInterval()-1);
            
            try {
                Thread.sleep(updatePeriodMS);
            } catch (InterruptedException ex) {          }
            
            if (Math.random() > getMissingRate()) {
                
                signal  = (float) nextSignal(n.time());

                signal *= 1.0 + (Math.random()-0.5f)* 2f * getNoiseRate();

                observed.add((int) n.time(), signal);
                
                
                val = d.i(signal);

                
                
                
                
                if (lastVal!=val) {
                    /*if (lastVal!=-1)
                        n.believe("<{x} --> y"+lastVal+">",Tense.Present, 0.0f, 0.95f);*/
                                        
                    n.believe("<{x} --> y"+val+">",Tense.Present, 1.0f, 0.99f);
                }
                
                
                
                //chg.set("<{x} --> y"+val+">. :|:");

                observedDiscrete.add((int)n.time(), val);
                
                lastVal = val;
                
            }

        }
    }
    
    
   
    public static void main(String[] args) throws Narsese.InvalidInputException, InterruptedException {
        
        new PredictGUI();


    }

    private NSlider addSlider(String label, AtomicDouble value, float min, float max) {
        NSlider slider;
        add(new JLabel(label), cons);        
        add(slider = new NSlider(value, min, max), cons);        
        return slider;
    }


}
