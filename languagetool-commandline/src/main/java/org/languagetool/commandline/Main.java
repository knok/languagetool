/* LanguageTool, a natural language style checker 
 * Copyright (C) 2005 Daniel Naber (http://www.danielnaber.de)
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.commandline;

import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.Languages;
import org.languagetool.MultiThreadedJLanguageTool;
import org.languagetool.bitext.TabBitextReader;
import org.languagetool.language.English;
import org.languagetool.language.LanguageIdentifier;
import org.languagetool.rules.Rule;
import org.languagetool.rules.bitext.BitextRule;
import org.languagetool.rules.patterns.PatternRule;
import org.languagetool.tools.JnaTools;
import org.languagetool.tools.Tools;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

import static org.languagetool.tools.StringTools.*;

/**
 * The command line tool to check plain text files.
 * 
 * @author Daniel Naber
 */
class Main {

  /* maximum file size to read in a single read */
  private static final int MAX_FILE_SIZE = 64000;

  private final boolean verbose;
  private final boolean apiFormat;
  private final boolean taggerOnly;
  private final boolean applySuggestions;
  private final boolean autoDetect;
  private final boolean singleLineBreakMarksParagraph;
  private final String[] enabledRules;
  private final String[] disabledRules;
  private final Language motherTongue;
  
  private JLanguageTool lt;
  private boolean profileRules;
  private boolean bitextMode;
  private JLanguageTool srcLt;
  private List<BitextRule> bRules;
  private Rule currentRule;

  Main(CommandLineOptions options) throws IOException {
    this.verbose = options.isVerbose();
    this.apiFormat = options.isApiFormat();
    this.taggerOnly = options.isTaggerOnly();
    this.applySuggestions = options.isApplySuggestions();
    this.autoDetect = options.isAutoDetect();
    this.enabledRules = options.getEnabledRules();
    this.disabledRules = options.getDisabledRules();
    this.motherTongue = options.getMotherTongue();
    this.singleLineBreakMarksParagraph = options.isSingleLineBreakMarksParagraph();
    profileRules = false;
    bitextMode = false;
    srcLt = null;
    bRules = null;
    lt = new MultiThreadedJLanguageTool(options.getLanguage(), motherTongue);
    if (options.getLanguageModel() != null) {
      lt.activateLanguageModelRules(options.getLanguageModel());
    }
    Tools.selectRules(lt, disabledRules, enabledRules, options.isUseEnabledOnly());
  }

  boolean isSpellCheckingActive() {
    List<Rule> rules = lt.getAllActiveRules();
    for (Rule rule : rules) {
      if (rule.isDictionaryBasedSpellingRule()) {
        return true;
      }
    }
    return false;
  }
  
  JLanguageTool getJLanguageTool() {
    return lt;
  }
  
  private void setListUnknownWords(final boolean listUnknownWords) {
    lt.setListUnknownWords(listUnknownWords);
  }
  
  private void cleanUp() {
    JLanguageTool.removeTemporaryFiles();
  }
  
  private void setProfilingMode() {
    profileRules = true;
  }

  private void setBitextMode(final Language sourceLang,
      final String[] disabledRules, final String[] enabledRules, final File bitextRuleFile) throws IOException, ParserConfigurationException, SAXException {
    bitextMode = true;
    final Language target = lt.getLanguage();
    lt = new MultiThreadedJLanguageTool(target, null);
    srcLt = new MultiThreadedJLanguageTool(sourceLang);
    Tools.selectRules(lt, disabledRules, enabledRules);
    Tools.selectRules(srcLt, disabledRules, enabledRules);
    bRules = Tools.getBitextRules(sourceLang, lt.getLanguage(), bitextRuleFile);

    List<BitextRule> bRuleList = new ArrayList<>(bRules);
    for (final BitextRule bitextRule : bRules) {
      for (final String disabledRule : disabledRules) {
        if (bitextRule.getId().equals(disabledRule)) {
          bRuleList.remove(bitextRule);
        }
      }
    }
    bRules = bRuleList;
    if (enabledRules.length > 0) {
      bRuleList = new ArrayList<>();
      for (final String enabledRule : enabledRules) {
        for (final BitextRule bitextRule : bRules) {
          if (bitextRule.getId().equals(enabledRule)) {
            bRuleList.add(bitextRule);
          }
        }
      }
      bRules = bRuleList;
    }
  }

  private void runOnFile(final String filename, final String encoding,
      final boolean listUnknownWords, final boolean xmlFiltering) throws IOException {
    boolean oneTime = false;
    if (!isStdIn(filename)) {
      if (autoDetect) {
        Language language = detectLanguageOfFile(filename, encoding);
        if (language == null) {
          System.err.println("Could not detect language well enough, using English");
          language = new English();
        }
        changeLanguage(language, motherTongue, disabledRules, enabledRules);
        System.err.println("Using " + language.getName() + " for file " + filename);
      }
      final File file = new File(filename);
      // run once on file if the file size < MAX_FILE_SIZE or
      // when we use the bitext mode (we use a bitext reader
      // instead of a direct file access)
      oneTime = file.length() < MAX_FILE_SIZE || bitextMode;
    }
    if (oneTime) {
      runOnFileInOneGo(filename, encoding, listUnknownWords, xmlFiltering);
    } else {
      runOnFileLineByLine(filename, encoding, listUnknownWords);
    }
  }

  private void runOnFileInOneGo(String filename, String encoding, boolean listUnknownWords, boolean xmlFiltering) throws IOException {
    if (bitextMode) {
      final TabBitextReader reader = new TabBitextReader(filename, encoding);
      if (applySuggestions) {
        CommandLineTools.correctBitext(reader, srcLt, lt, bRules);
      } else {
        CommandLineTools.checkBitext(reader, srcLt, lt, bRules, apiFormat);
      }
    } else {
      final String text = getFilteredText(filename, encoding, xmlFiltering);
      if (applySuggestions) {
        System.out.print(Tools.correctText(text, lt));
      } else if (profileRules) {
        CommandLineTools.profileRulesOnText(text, lt);
      } else if (!taggerOnly) {
        CommandLineTools.checkText(text, lt, apiFormat, 0);
      } else {
        CommandLineTools.tagText(text, lt);
      }
      if (listUnknownWords) {
        System.out.println("Unknown words: " + lt.getUnknownWords());
      }
    }
  }

  private void runOnFileLineByLine(String filename, String encoding, boolean listUnknownWords) throws IOException {
    if (verbose) {
      lt.setOutput(System.err);
    }
    if (!apiFormat && !applySuggestions) {
      if (isStdIn(filename)) {
        System.err.println("Working on STDIN...");
      } else {
        System.err.println("Working on " + filename + "...");
      }
    }
    if (profileRules && isStdIn(filename)) {
      throw new IllegalArgumentException("Profiling mode cannot be used with input from STDIN");
    }
    int runCount = 1;
    final List<Rule> rules = lt.getAllActiveRules();
    if (profileRules) {
      System.out.printf("Testing %d rules\n", rules.size());
      System.out.println("Rule ID\tTime\tSentences\tMatches\tSentences per sec.");
      runCount = rules.size();
    }
    int lineOffset = 0;
    int tmpLineOffset = 0;
    final List<String> unknownWords = new ArrayList<>();
    handleLine(XmlPrintMode.START_XML, 0, new StringBuilder());
    StringBuilder sb = new StringBuilder();
    for (int ruleIndex = 0; !rules.isEmpty() && ruleIndex < runCount; ruleIndex++) {
      currentRule = rules.get(ruleIndex);
      int matches = 0;
      long sentences = 0;
      final long startTime = System.currentTimeMillis();
      try (
        InputStreamReader isr = getInputStreamReader(filename, encoding);
        BufferedReader br = new BufferedReader(isr)
      ) {
        String line;
        int lineCount = 0;
        while ((line = br.readLine()) != null) {
          sb.append(line);
          lineCount++;
          // to detect language from the first input line
          if (lineCount == 1 && autoDetect) {
            Language language = detectLanguageOfString(line);
            if (language == null) {
              System.err.println("Could not detect language well enough, using English");
              language = new English();
            }
            System.err.println("Language used is: " + language.getName());
            language.getSentenceTokenizer().setSingleLineBreaksMarksParagraph(
                    singleLineBreakMarksParagraph);
            changeLanguage(language, motherTongue, disabledRules, enabledRules);
          }
          sb.append('\n');
          tmpLineOffset++;

          if (isBreakPoint(sb, line)) {
            matches += handleLine(XmlPrintMode.CONTINUE_XML, lineOffset, sb);
            sentences += lt.getSentenceCount();
            if (profileRules) {
              sentences += lt.sentenceTokenize(sb.toString()).size();
            }
            rememberUnknownWords(listUnknownWords, unknownWords);
            sb = new StringBuilder();
            lineOffset = tmpLineOffset;
          }
        }
      } finally {
        matches += handleLine(XmlPrintMode.END_XML, tmpLineOffset - 1, sb);
        if (sb.length() > 0) {
          sentences += lt.getSentenceCount();
          if (profileRules) {
            sentences += lt.sentenceTokenize(sb.toString()).size();
          }
          rememberUnknownWords(listUnknownWords, unknownWords);
        }
        printTimingInformation(listUnknownWords, rules, unknownWords, ruleIndex, matches, sentences, startTime);
      }
    }
  }

  private boolean isBreakPoint(StringBuilder sb, String line) {
    return lt.getLanguage().getSentenceTokenizer().singleLineBreaksMarksPara()
        || "".equals(line) || sb.length() >= MAX_FILE_SIZE;
  }

  private void rememberUnknownWords(boolean listUnknownWords, List<String> unknownWords) {
    if (listUnknownWords && !taggerOnly) {
      for (String word : lt.getUnknownWords()) {
        if (!unknownWords.contains(word)) {
          unknownWords.add(word);
        }
      }
    }
  }

  private InputStreamReader getInputStreamReader(String filename, String encoding)
          throws UnsupportedEncodingException, FileNotFoundException {
    final InputStreamReader isr;
    if (!isStdIn(filename)) {
      final File file = new File(filename);
      if (encoding != null) {
        isr = new InputStreamReader(new BufferedInputStream(
            new FileInputStream(file)), encoding);
      } else {
        isr = new InputStreamReader(new BufferedInputStream(
            new FileInputStream(file)));
      }
    } else {
      if (encoding != null) {
        isr = new InputStreamReader(new BufferedInputStream(System.in), encoding);
      } else {
        isr = new InputStreamReader(new BufferedInputStream(System.in));
      }
    }
    return isr;
  }

  private boolean isStdIn(String filename) {
    return "-".equals(filename);
  }

  private void printTimingInformation(final boolean listUnknownWords, final List<Rule> rules,
      final List<String> unknownWords, final int ruleIndex, final int matches, final long sentences, final long startTime) {
    if (!applySuggestions) {
      final long endTime = System.currentTimeMillis();
      final long time = endTime - startTime;
      final float timeInSeconds = time / 1000.0f;
      final float sentencesPerSecond = sentences / timeInSeconds;
      if (apiFormat) {
        System.out.println("<!--");
      }
      if (profileRules) {
        System.out.printf(Locale.ENGLISH,
            "%s\t%d\t%d\t%d\t%.1f", rules.get(ruleIndex).getId(),
            time, sentences, matches, sentencesPerSecond);
        System.out.println();
      } else {
        System.out.printf(Locale.ENGLISH,
            "Time: %dms for %d sentences (%.1f sentences/sec)", time,
            sentences, sentencesPerSecond);
        System.out.println();
      }
      if (listUnknownWords) {
        Collections.sort(unknownWords);
        System.out.println("Unknown words: " + unknownWords);
      }
      if (apiFormat) {
        System.out.println("-->");
      }
    }
  }

  private int handleLine(final XmlPrintMode mode, final int lineOffset,
      final StringBuilder sb) throws IOException {
    int matches = 0;
    String s = filterXML(sb.toString());
    if (applySuggestions) {
      System.out.print(Tools.correctText(s, lt));
    } else if (profileRules) {
      matches += Tools.profileRulesOnLine(s, lt, currentRule);
    } else if (!taggerOnly) {
      matches += CommandLineTools.checkText(s, lt, apiFormat, -1, lineOffset, matches, mode);
    } else {
      CommandLineTools.tagText(s, lt);
    }
    return matches;
  }

  private void runRecursive(final String filename, final String encoding,
      final boolean listUnknown, final boolean xmlFiltering) {
    final File dir = new File(filename);
    final File[] files = dir.listFiles();
    if (files == null) {
      throw new IllegalArgumentException(dir.getAbsolutePath() + " is not a directory, cannot use recursion");
    }
    for (final File file : files) {
      try {
        if (file.isDirectory()) {
          runRecursive(file.getAbsolutePath(), encoding, listUnknown, xmlFiltering);
        } else {
          runOnFile(file.getAbsolutePath(), encoding, listUnknown, xmlFiltering);
        }
      } catch (Exception e) {
        throw new RuntimeException("Could not check text in file " + file, e);
      }
    }    
  }

  /**
   * Loads filename and filters out XML. Note that the XML
   * filtering can lead to incorrect positions in the list of matching rules.
   */
  private String getFilteredText(final String filename, final String encoding, boolean xmlFiltering) throws IOException {
    if (verbose) {
      lt.setOutput(System.err);
    }
    if (!apiFormat && !applySuggestions) {
      System.out.println("Working on " + filename + "...");
    }
    // don't use StringTools.readStream() as that might add newlines which aren't there:
    final String fileContents = streamToString(new FileInputStream(filename), encoding != null ? encoding : Charset.defaultCharset().name());
    if (xmlFiltering) {
      return filterXML(fileContents);
    } else {
      return fileContents;
    }
  }

  private void changeLanguage(Language language, Language motherTongue,
                              String[] disabledRules, String[] enabledRules) {
    try {
      lt = new MultiThreadedJLanguageTool(language, motherTongue);
      Tools.selectRules(lt, disabledRules, enabledRules);
      if (verbose) {
        lt.setOutput(System.err);
      }
    } catch (Exception e) {
      throw new RuntimeException("Could not create LanguageTool instance for language " + language, e);
    }
  }

  /**
   * Command line tool to check plain text files.
   */
  public static void main(final String[] args) throws IOException, ParserConfigurationException, SAXException {
    JnaTools.setBugWorkaroundProperty();
    final CommandLineParser commandLineParser = new CommandLineParser();
    CommandLineOptions options = null;
    try {
       options = commandLineParser.parseOptions(args);
    } catch (WrongParameterNumberException e) {
      commandLineParser.printUsage();
      System.exit(1);
    } catch (IllegalArgumentException e) {
      System.err.println(e.toString());
      System.exit(1);
    } catch (UnknownParameterException e) {
      if (e.getMessage() != null) {
        System.err.println(e.getMessage());
      } else {
        System.err.println(e.toString());
      }
      commandLineParser.printUsage(System.err);
      System.exit(1);
    }
    if (options.isPrintUsage()) {
      commandLineParser.printUsage();
      System.exit(1);
    }
    if (options.isPrintVersion()) {
      System.out.println("LanguageTool version " + JLanguageTool.VERSION + " (" + JLanguageTool.BUILD_DATE + ")");
      System.exit(0);
    }
    if (options.isPrintLanguages()) {
      printLanguages();
      System.exit(0);
    }

    if (options.getFilename() == null) {
      options.setFilename("-");
    }

    String languageHint = null;
    if (options.getLanguage() == null) {
      if (!options.isApiFormat() && !options.isAutoDetect()) {
        System.err.println("No language specified, using English (no spell checking active, " +
                "specify a language variant like 'en-GB' if available)");
      }
      options.setLanguage(new English());
    } else if (!options.isApiFormat() && !options.isApplySuggestions()) {
      languageHint = "Expected text language: " + options.getLanguage().getName();
    }

    options.getLanguage().getSentenceTokenizer().setSingleLineBreaksMarksParagraph(
            options.isSingleLineBreakMarksParagraph());

    if (options.getRuleFile() != null) {
      options.getLanguage().addExternalRuleFile(options.getRuleFile());
    }

    final Main prg = new Main(options);
    if (options.getFalseFriendFile() != null) {
      List<PatternRule> ffRules = prg.lt.loadFalseFriendRules(options.getFalseFriendFile());
      for (PatternRule ffRule : ffRules) {
        prg.lt.addRule(ffRule);
      }
    }
    if (prg.lt.getAllActiveRules().size() == 0) {
      throw new RuntimeException("WARNING: No rules are active. Please make sure your rule ids are correct: " +
              Arrays.toString(options.getEnabledRules()));
    }
    if (languageHint != null) {
      String spellHint = prg.isSpellCheckingActive() ?
              "" : " (no spell checking active, specify a language variant like 'en-GB' if available)";
      System.err.println(languageHint + spellHint);
    }
    prg.setListUnknownWords(options.isListUnknown());
    if (options.isProfile()) {
      prg.setProfilingMode();
    }
    if (options.isBitext()) {
      if (options.getMotherTongue() == null) {
        throw new IllegalArgumentException("You have to set the source language (as mother tongue) in bitext mode");
      }
      File bitextRuleFile = options.getBitextRuleFile() != null ? new File(options.getBitextRuleFile()) : null;
      prg.setBitextMode(options.getMotherTongue(), options.getDisabledRules(), options.getEnabledRules(), bitextRuleFile);
    }
    if (options.isRecursive()) {
      prg.runRecursive(options.getFilename(), options.getEncoding(), options.isListUnknown(), options.isXmlFiltering());
    } else {
      prg.runOnFile(options.getFilename(), options.getEncoding(), options.isListUnknown(), options.isXmlFiltering());
    }
    prg.cleanUp();
  }

  private static void printLanguages() {
    final List<String> languages = new ArrayList<>();
    for (Language language : Languages.get()) {
      languages.add(language.getShortNameWithCountryAndVariant() + " " + language.getName());
    }
    Collections.sort(languages);
    for (String s : languages) {
      System.out.println(s);
    }
  }

  private static Language detectLanguageOfFile(final String filename, final String encoding) throws IOException {
    final String text = readStream(new FileInputStream(filename), encoding);
    return detectLanguageOfString(text);
  }

  private static Language detectLanguageOfString(final String text) {
    LanguageIdentifier identifier = new LanguageIdentifier();
    return identifier.detectLanguage(text);
  }

}
