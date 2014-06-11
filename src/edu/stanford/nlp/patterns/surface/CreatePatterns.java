package edu.stanford.nlp.patterns.surface;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.patterns.surface.ConstantsAndVariables;
import edu.stanford.nlp.sequences.SeqClassifierFlags;
import edu.stanford.nlp.util.Execution;
import edu.stanford.nlp.util.StringUtils;
import edu.stanford.nlp.util.Triple;
import edu.stanford.nlp.util.Execution.Option;
import edu.stanford.nlp.util.logging.Redwood;

public class CreatePatterns {

  /**
   * Use POS tag restriction in the target term: One of this and <code>addPatWithoutPOS</code> has to be true.
   */
  @Option(name = "usePOS4Pattern")
  public boolean usePOS4Pattern = true;
  
  /**
   * Add patterns without POS restriction as well: One of this and <code>usePOS4Pattern</code> has to be true.
   */
  @Option(name = "addPatWithoutPOS")
  public boolean addPatWithoutPOS = true;

  /**
   * Consider contexts longer or equal to these many tokens.
   */
  @Option(name = "minWindow4Pattern")
  public int minWindow4Pattern = 2;

  /**
   * Consider contexts less than or equal to these many tokens -- total of left and right contexts be can double of this.
   */
  @Option(name = "maxWindow4Pattern")
  public int maxWindow4Pattern = 4;

  /**
   * Consider contexts on the left of a token.
   */
  @Option(name = "usePreviousContext")
  public boolean usePreviousContext = true;

  /**
   * Consider contexts on the right of a token.
   */
  @Option(name = "useNextContext")
  public boolean useNextContext = false;;

  /**
   * If the whole (either left or right) context is just stop words, add the pattern only if number of tokens is equal or more than this. 
   * This is get patterns like "I am on X" but ignore "on X".
   */
  @Option(name = "numMinStopWordsToAdd")
  public int numMinStopWordsToAdd = 3;

  /**
   * Initials of all POS tags to use if <code>usePOS4Pattern<code> is true, separated by comma.
   */
  @Option(name = "allowedTagsInitials")
  public String allowedTagsInitialsStr = "N,J";
  
  private List<String> allowedTagsInitials = null;

  /**
   * Ignore words like "a", "an", "the" when matching a pattern.
   */
  @Option(name = "useFillerWordsInPat")
  public boolean useFillerWordsInPat = true;

  /**
   * allow to match stop words before a target term. This is to match something like "I am on some X" if the pattern is "I am on X"
   */
  @Option(name = "useStopWordsBeforeTerm")
  public boolean useStopWordsBeforeTerm = false;

  String channelNameLogger = "createpatterns";

  ConstantsAndVariables constVars;

  public CreatePatterns(Properties props, ConstantsAndVariables constVars)
      throws IOException {
    this.constVars = constVars;
    Execution.fillOptions(ConstantsAndVariables.class, props);
    constVars.setUp(props);
    setUp(props);
  }

  void setUp(Properties props) {
    Execution.fillOptions(this, props);

    allowedTagsInitials = Arrays.asList(allowedTagsInitialsStr.split(","));
    if (!addPatWithoutPOS && !this.usePOS4Pattern) {
      throw new RuntimeException(
          "addPatWithoutPOS and usePOS4Pattern both cannot be false ");
    }
  }

  boolean doNotUse(String word, Set<String> stopWords) {
    if (stopWords.contains(word.toLowerCase())
        || constVars.ignoreWordRegex.matcher(word).matches())
      return true;
    else
      return false;

  }

  Triple<Boolean, String, String> getContextTokenStr(CoreLabel tokenj){
    String strgeneric = "";
    String strOriginal = "";
    boolean isLabeledO = true;
    for (Entry<String, Class> e : constVars.answerClass.entrySet()) {
      if (!tokenj.get(e.getValue()).equals(constVars.backgroundSymbol)) {
        isLabeledO = false;
        if (strgeneric.isEmpty()) {
          strgeneric = "{" + e.getKey() + ":" + e.getKey() + "}";
          strOriginal = e.getKey();
        } else {
          strgeneric += " | " + "{" + e.getKey() + ":" + e.getKey() + "}";
          strOriginal += "|" + e.getKey();
        }
      }
    }
    
    for (Entry<String, Class> e : constVars.getGeneralizeClasses().entrySet()) {
      if (!tokenj.get(e.getValue()).equals(constVars.backgroundSymbol)) {
        isLabeledO = false;
        if (strgeneric.isEmpty()) {
          strgeneric = "{" + e.getKey() + ":" + tokenj.get(e.getValue()) + "}";
          strOriginal = e.getKey();
        } else {
          strgeneric += " | " + "{" + e.getKey() + ":" + tokenj.get(e.getValue()) + "}";
          strOriginal += "|" + e.getKey();
        }
      }
    }
    
    if(constVars.useContextNERRestriction){
      String nerTag = tokenj.get(CoreAnnotations.NamedEntityTagAnnotation.class);
      if(!nerTag.equals(SeqClassifierFlags.DEFAULT_BACKGROUND_SYMBOL)){
        isLabeledO = false;
        if (strgeneric.isEmpty()) {
          strgeneric = "{ner:" +  nerTag + "}";
          strOriginal = nerTag;
        } else {
          strgeneric += " | " + "{ner:" + nerTag + "}";
          strOriginal += "|" + nerTag;
        }
      }
    }
    
    return new Triple<Boolean, String, String>(isLabeledO, strgeneric, strOriginal);
  }
  
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>> getContext(
      String label, List<CoreLabel> sent, int i) {

    Set<SurfacePattern> prevpatterns = new HashSet<SurfacePattern>();
    Set<SurfacePattern> nextpatterns = new HashSet<SurfacePattern>();
    Set<SurfacePattern> prevnextpatterns = new HashSet<SurfacePattern>();
    CoreLabel token = sent.get(i);
    String fulltag = token.tag();
    String tag = fulltag.substring(0, Math.min(fulltag.length(), 2));
    String nerTag = token.get(CoreAnnotations.NamedEntityTagAnnotation.class);
    for (int maxWin = 1; maxWin <= maxWindow4Pattern; maxWin++) {
      List<String> previousTokens = new ArrayList<String>();
      String originalPrevStr = "", originalNextStr = "";
      List<String> nextTokens = new ArrayList<String>();

      int numStopWordsprev = 0, numStopWordsnext = 0;
      // int numPrevTokensSpecial = 0, numNextTokensSpecial = 0;
      int numNonStopWordsNext = 0, numNonStopWordsPrev = 0;
      boolean useprev = false, usenext = false;

      if (usePreviousContext) {
        //int j = Math.max(0, i - 1);
        int j = i -1;
        int numTokens = 0;
        while (numTokens < maxWin && j >= 0) {
          // for (int j = Math.max(i - maxWin, 0); j < i; j++) {
          CoreLabel tokenj = sent.get(j);
          
          String tokenjStr;
          if(constVars.useLemmaContextTokens)
            tokenjStr = tokenj.lemma();
          else
            tokenjStr = tokenj.word();
          
          // do not use this word in context consideration
          if (useFillerWordsInPat
              && constVars.fillerWords.contains(tokenj.word().toLowerCase())) {
            j--;
            continue;
          }
          if (!tokenj.containsKey(constVars.answerClass.get(label))) {
            throw new RuntimeException("how come the class "
                + constVars.answerClass.get(label) + " for token "
                + tokenj.word() + " in " + sent + " is not set");
          }
        
          Triple<Boolean, String, String> tr = this.getContextTokenStr(tokenj);
          boolean isLabeledO = tr.first;
          String strgeneric = tr.second;
          String strOriginal = tr.third;

          if (!isLabeledO) {
            // numPrevTokensSpecial++;
            previousTokens.add(0, "[" + strgeneric + "]");
            // previousTokens.add(0,
            // "[{answer:"
            // + tokenj.get(constVars.answerClass.get(label)).toString()
            // + "}]");
            originalPrevStr = strOriginal + " " + originalPrevStr;
            numNonStopWordsPrev++;
          } else if (tokenj.word().startsWith("http")) {
            useprev = false;
            previousTokens.clear();
            originalPrevStr = "";
            break;
          } else {
            String str = SurfacePattern.getContextStr(tokenj, constVars.useLemmaContextTokens, constVars.matchLowerCaseContext);
            previousTokens.add(0, str);
            originalPrevStr = tokenjStr + " " + originalPrevStr;
            if (doNotUse(tokenjStr, constVars.getStopWords())) {
              numStopWordsprev++;
            } else
              numNonStopWordsPrev++;
          }
          numTokens++;
          j--;
        }
      }

      if (useNextContext) {
        int numTokens = 0;
        int j = i + 1;
        while (numTokens < maxWin && j < sent.size()) {
          // for (int j = i + 1; j < sent.size() && j <= i + maxWin; j++) {
          CoreLabel tokenj = sent.get(j);
          
          String tokenjStr;
          if(constVars.useLemmaContextTokens)
            tokenjStr = tokenj.lemma();
          else
            tokenjStr = tokenj.word();
          
          // do not use this word in context consideration
          if (useFillerWordsInPat
              && constVars.fillerWords.contains(tokenj.word().toLowerCase())) {
            j++;
            continue;
          }
          if (!tokenj.containsKey(constVars.answerClass.get(label))) {
            throw new RuntimeException(
                "how come the dict annotation for token " + tokenj.word()
                    + " in " + sent + " is not set");
          }

          Triple<Boolean, String, String> tr = this.getContextTokenStr(tokenj);
          boolean isLabeledO = tr.first;
          String strgeneric = tr.second;
          String strOriginal = tr.third;
          
          // boolean isLabeledO = tokenj.get(constVars.answerClass.get(label))
          // .equals(SeqClassifierFlags.DEFAULT_BACKGROUND_SYMBOL);
          if (!isLabeledO) {
            // numNextTokensSpecial++;
            numNonStopWordsNext++;
            nextTokens.add("[" + strgeneric + "]");
            // nextTokens.add("[{" + label + ":"
            // + tokenj.get(constVars.answerClass.get(label)).toString()
            // + "}]");
            originalNextStr += " " + strOriginal;
            // originalNextStr += " "
            // + tokenj.get(constVars.answerClass.get(label)).toString();
          } else if (tokenj.word().startsWith("http")) {
            usenext = false;
            nextTokens.clear();
            originalNextStr = "";
            break;
          } else {// if (!tokenj.word().matches("[.,?()]")) {
            String str = SurfacePattern.getContextStr(tokenj, constVars.useLemmaContextTokens, constVars.matchLowerCaseContext);
            nextTokens.add(str);
            originalNextStr += " " + tokenjStr;
            if (doNotUse(tokenjStr, constVars.getStopWords())) {
              numStopWordsnext++;
            } else
              numNonStopWordsNext++;
          }
          j++;
          numTokens++;
        }
      }
      String prevContext = null, nextContext = null;

      // int numNonSpecialPrevTokens = previousTokens.size()
      // - numPrevTokensSpecial;
      // int numNonSpecialNextTokens = nextTokens.size() - numNextTokensSpecial;

      String fw = " ";
      if (useFillerWordsInPat)
        fw = " $FILLER{0,2} ";

      String sw = "";
      if (useStopWordsBeforeTerm) {
        sw = " $STOPWORD{0,2} ";
      }

      // if (previousTokens.size() >= minWindow4Pattern
      // && (numStopWordsprev < numNonSpecialPrevTokens ||
      // numNonSpecialPrevTokens > numMinStopWordsToAdd)) {
      if (previousTokens.size() >= minWindow4Pattern
          && (numNonStopWordsPrev > 0 || numStopWordsprev > numMinStopWordsToAdd)) {
        prevContext = StringUtils.join(previousTokens, fw);
        String str = prevContext + fw + sw;
        PatternToken twithoutPOS = null;
        if (addPatWithoutPOS) {
          twithoutPOS = new PatternToken(tag, false,
              constVars.numWordsCompound > 1, constVars.numWordsCompound, nerTag, constVars.useTargetNERRestriction);
          // twithoutPOS.setPreviousContext(sw);
        }

        PatternToken twithPOS = null;
        if (usePOS4Pattern) {
          twithPOS = new PatternToken(tag, true,
              constVars.numWordsCompound > 1, constVars.numWordsCompound, nerTag, constVars.useTargetNERRestriction);
          // twithPOS.setPreviousContext(sw);
        }

        if (isASCII(prevContext)) {
          if (previousTokens.size() >= minWindow4Pattern) {
            if (twithoutPOS != null) {
              SurfacePattern pat = new SurfacePattern(str, twithoutPOS, "",
                  originalPrevStr, "");
              prevpatterns.add(pat);
            }
            if (twithPOS != null) {
              SurfacePattern patPOS = new SurfacePattern(str, twithPOS, "",
                  originalPrevStr, "");
              prevpatterns.add(patPOS);
            }
          }
          useprev = true;
        }
      }

      // if (nextTokens.size() > 0
      // && (numStopWordsnext < numNonSpecialNextTokens ||
      // numNonSpecialNextTokens > numMinStopWordsToAdd)) {
      if (nextTokens.size() > 0
          && (numNonStopWordsNext > 0 || numStopWordsnext > numMinStopWordsToAdd)) {
        nextContext = StringUtils.join(nextTokens, fw);
        String str = "";

        PatternToken twithoutPOS = null;
        if (addPatWithoutPOS) {
          twithoutPOS = new PatternToken(tag, false,
              constVars.numWordsCompound > 1, constVars.numWordsCompound, nerTag, constVars.useTargetNERRestriction);
          // twithoutPOS.setNextContext(sw);
        }
        PatternToken twithPOS = null;
        if (usePOS4Pattern) {
          twithPOS = new PatternToken(tag, true,
              constVars.numWordsCompound > 1, constVars.numWordsCompound, nerTag, constVars.useTargetNERRestriction);
          // twithPOS.setNextContext(sw);
        }
        str += sw + fw + nextContext;

        if (nextTokens.size() >= minWindow4Pattern) {
          if (twithoutPOS != null) {
            SurfacePattern pat = new SurfacePattern("", twithoutPOS, str, "",
                originalNextStr);
            nextpatterns.add(pat);
          }
          if (twithPOS != null) {
            SurfacePattern patPOS = new SurfacePattern("", twithPOS, str, "",
                originalNextStr);
            nextpatterns.add(patPOS);
          }

        }
        usenext = true;

      }

      if (useprev && usenext) {
        String strprev = prevContext + fw + sw;

        PatternToken twithoutPOS = null;
        if (addPatWithoutPOS) {
          twithoutPOS = new PatternToken(tag, false,
              constVars.numWordsCompound > 1, constVars.numWordsCompound, nerTag, constVars.useTargetNERRestriction);
          // twithoutPOS.setNextContext(sw);
          // twithoutPOS.setPreviousContext(sw);
        }

        PatternToken twithPOS = null;
        if (usePOS4Pattern) {
          twithPOS = new PatternToken(tag, true,
              constVars.numWordsCompound > 1, constVars.numWordsCompound, nerTag, constVars.useTargetNERRestriction);
          // twithPOS.setNextContext(sw);
          // twithPOS.setPreviousContext(sw);
        }

        String strnext = sw + fw + nextContext;
        if (previousTokens.size() + nextTokens.size() >= minWindow4Pattern) {

          if (twithoutPOS != null) {
            SurfacePattern pat = new SurfacePattern(strprev, twithoutPOS,
                strnext, originalPrevStr, originalNextStr);
            prevnextpatterns.add(pat);
          }

          if (twithPOS != null) {
            SurfacePattern patPOS = new SurfacePattern(strprev, twithPOS,
                strnext, originalPrevStr, originalNextStr);
            prevnextpatterns.add(patPOS);
          }
        }

      }
    }

    Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>> patterns = new Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>>(
        prevpatterns, nextpatterns, prevnextpatterns);
    // System.out.println("For word " + sent.get(i) + " in sentence " + sent +
    // " prev patterns are " + prevpatterns);
    // System.out.println("For word " + sent.get(i) + " in sentence " + sent +
    // " next patterns are " + nextpatterns);
    // System.out.println("For word " + sent.get(i) + " in sentence " + sent +
    // " prevnext patterns are " + prevnextpatterns);
    return patterns;
  }

  public static boolean isASCII(String text) {

    Charset charset = Charset.forName("US-ASCII");
    String checked = new String(text.getBytes(charset), charset);
    return checked.equals(text);// && !text.contains("+") &&
                                // !text.contains("*");// && !
                                // text.contains("$") && !text.contains("\"");

  }

  Map<String, Map<Integer, Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>>>> getAllPatterns(
      String label, Map<String, List<CoreLabel>> sents)
      throws InterruptedException, ExecutionException {

    Map<String, Map<Integer, Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>>>> patternsForEachToken = new HashMap<String, Map<Integer, Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>>>>();
    List<String> keyset = new ArrayList<String>(sents.keySet());

    int num = 0;
    if (constVars.numThreads == 1)
      num = keyset.size();
    else
      num = keyset.size() / (constVars.numThreads - 1);
    ExecutorService executor = Executors
        .newFixedThreadPool(constVars.numThreads);
    Redwood.log(Redwood.FORCE, channelNameLogger,
        "keyset size is " + keyset.size());
    List<Future<Map<String, Map<Integer, Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>>>>>> list = new ArrayList<Future<Map<String, Map<Integer, Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>>>>>>();
    for (int i = 0; i < constVars.numThreads; i++) {
      Redwood.log(Redwood.FORCE, channelNameLogger, "assigning from " + i * num
          + " till " + Math.min(keyset.size(), (i + 1) * num));

      Callable<Map<String, Map<Integer, Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>>>>> task = null;
      List<String> ids = keyset.subList(i * num,
          Math.min(keyset.size(), (i + 1) * num));
      task = new CreatePatternsThread(label, sents, ids);

      Future<Map<String, Map<Integer, Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>>>>> submit = executor
          .submit(task);
      list.add(submit);
    }

    // Now retrieve the result

    for (Future<Map<String, Map<Integer, Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>>>>> future : list) {
      patternsForEachToken.putAll(future.get());
    }
    executor.shutdown();
    return patternsForEachToken;
  }

  public class CreatePatternsThread
      implements
      Callable<Map<String, Map<Integer, Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>>>>> {

    String label;
    // Class otherClass;
    Map<String, List<CoreLabel>> sents;
    List<String> sentIds;

    public CreatePatternsThread(String label,
        Map<String, List<CoreLabel>> sents, List<String> sentIds) {

      this.label = label;
      // this.otherClass = otherClass;
      this.sents = sents;
      this.sentIds = sentIds;
    }

    @Override
    public Map<String, Map<Integer, Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>>>> call()
        throws Exception {
      Map<String, Map<Integer, Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>>>> patternsForTokens = new HashMap<String, Map<Integer, Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>>>>();

      for (String id : sentIds) {
        List<CoreLabel> sent = sents.get(id);

        Map<Integer, Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>>> p = new HashMap<Integer, Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>>>();
        for (int i = 0; i < sent.size(); i++) {
          p.put(
              i,
              new Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>>(
                  new HashSet<SurfacePattern>(), new HashSet<SurfacePattern>(),
                  new HashSet<SurfacePattern>()));
          CoreLabel token = sent.get(i);
          // do not create patterns around stop words!
          if (doNotUse(token.word(), constVars.getStopWords())) {
            continue;
          }
          boolean use = false;
          String tag = token.tag();
          if (allowedTagsInitials == null
              || allowedTagsInitials.get(0).equals("*"))
            use = true;
          else {
            for (String s : allowedTagsInitials) {
              if (tag.startsWith(s)) {
                use = true;
                break;
              }
            }
          }

          if (use) {
            Triple<Set<SurfacePattern>, Set<SurfacePattern>, Set<SurfacePattern>> pat = getContext(
                label, sent, i);
            p.put(i, pat);
          }
        }
        patternsForTokens.put(id, p);
      }
      return patternsForTokens;
    }

  }
}