/*
 * This file is a part of BSL Language Server.
 *
 * Copyright © 2018-2021
 * Alexey Sosnoviy <labotamy@gmail.com>, Nikita Gryzlov <nixel2007@gmail.com> and contributors
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * BSL Language Server is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * BSL Language Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with BSL Language Server.
 */
package com.github._1c_syntax.bsl.languageserver.diagnostics;

import com.github._1c_syntax.bsl.languageserver.context.DocumentContext;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticMetadata;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticParameter;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticSeverity;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticTag;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticType;
import com.github._1c_syntax.bsl.languageserver.diagnostics.typo.JLanguageToolPool;
import com.github._1c_syntax.bsl.languageserver.utils.Trees;
import com.github._1c_syntax.bsl.parser.BSLParser;
import com.github._1c_syntax.bsl.parser.BSLParserRuleContext;
import com.github._1c_syntax.utils.CaseInsensitivePattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.Token;
import org.apache.commons.lang3.StringUtils;
import org.languagetool.JLanguageTool;
import org.languagetool.language.AmericanEnglish;
import org.languagetool.language.Russian;
import org.languagetool.rules.RuleMatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@DiagnosticMetadata(
  type = DiagnosticType.CODE_SMELL,
  severity = DiagnosticSeverity.INFO,
  minutesToFix = 1,
  tags = {
    DiagnosticTag.BADPRACTICE
  }
)
@Slf4j
public class TypoDiagnostic extends AbstractDiagnostic {

  @Getter(lazy = true, value = AccessLevel.PRIVATE)
  private static final Map<String, JLanguageToolPool> languageToolPoolMap = Map.of(
    "en", new JLanguageToolPool(new AmericanEnglish()),
    "ru", new JLanguageToolPool(new Russian())
  );

  /**
   * Карта, хранящая результат проверки слова (ошибка/нет ошибки) в разрезе языков.
   */
  private static final Map<String, Map<String, Boolean>> checkedWords = Map.of(
    "en", new ConcurrentHashMap<>(),
    "ru", new ConcurrentHashMap<>()
  );

  private static final Pattern SPACES_PATTERN = Pattern.compile("\\s+");
  private static final Pattern QUOTE_PATTERN = Pattern.compile("\"");
  private static final String FORMAT_STRING_RU = "Л=|ЧЦ=|ЧДЦ=|ЧС=|ЧРД=|ЧРГ=|ЧН=|ЧВН=|ЧГ=|ЧО=|ДФ=|ДЛФ=|ДП=|БЛ=|БИ=";
  private static final String FORMAT_STRING_EN = "|L=|ND=|NFD=|NS=|NDS=|NGS=|NZ=|NLZ=|NG=|NN=|NF=|DF=|DLF=|DE=|BF=|BT=";
  private static final Pattern FORMAT_STRING_PATTERN =
    CaseInsensitivePattern.compile(FORMAT_STRING_RU + FORMAT_STRING_EN);

  private static final Integer[] rulesToFind = new Integer[]{
    BSLParser.RULE_string,
    BSLParser.RULE_lValue,
    BSLParser.RULE_var_name,
    BSLParser.RULE_subName
  };
  private static final Set<Integer> tokenTypes = Set.of(
    BSLParser.STRING,
    BSLParser.IDENTIFIER
  );

  private static final int DEFAULT_MIN_WORD_LENGTH = 3;
  private static final String DEFAULT_USER_WORDS_TO_IGNORE = "";

  @DiagnosticParameter(
    type = Integer.class,
    defaultValue = "" + DEFAULT_MIN_WORD_LENGTH
  )
  private int minWordLength = DEFAULT_MIN_WORD_LENGTH;

  @DiagnosticParameter(
    type = String.class
  )
  private String userWordsToIgnore = DEFAULT_USER_WORDS_TO_IGNORE;

  @Override
  public void configure(Map<String, Object> configuration) {
    super.configure(configuration);
    minWordLength = Math.max(minWordLength, DEFAULT_MIN_WORD_LENGTH);
  }

  private Set<String> getWordsToIgnore() {
    String delimiter = ",";
    String exceptions = SPACES_PATTERN.matcher(info.getResourceString("diagnosticExceptions")).replaceAll("");
    if (!userWordsToIgnore.isEmpty()) {
      exceptions = exceptions + delimiter + SPACES_PATTERN.matcher(userWordsToIgnore).replaceAll("");
    }

    return Arrays.stream(exceptions.split(delimiter))
      .collect(Collectors.toSet());
  }

  private static JLanguageTool acquireLanguageTool(String lang) {
    return getLanguageToolPoolMap().get(lang).checkOut();
  }

  private static void releaseLanguageTool(String lang, JLanguageTool languageTool) {
    getLanguageToolPoolMap().get(lang).checkIn(languageTool);
  }

  private Set<String> getTokenizedStringsFromTokens(
    DocumentContext documentContext,
    Map<String, List<Token>> tokensMap
  ) {
    StringBuilder text = new StringBuilder();
    Set<String> wordsToIgnore = getWordsToIgnore();

    Trees.findAllRuleNodes(documentContext.getAst(), rulesToFind).stream()
      .map(BSLParserRuleContext.class::cast)
      .flatMap(ruleContext -> ruleContext.getTokens().stream())
      .filter(token -> tokenTypes.contains(token.getType()))
      .filter(token -> !FORMAT_STRING_PATTERN.matcher(token.getText()).find())
      .forEach((Token token) -> {
          String curText = QUOTE_PATTERN.matcher(token.getText()).replaceAll("");
          var splitList = Arrays.asList(StringUtils.splitByCharacterTypeCamelCase(curText));
          splitList.stream()
            .filter(element -> element.length() >= minWordLength)
            .forEach(element -> tokensMap.computeIfAbsent(element, newElement -> new ArrayList<>()).add(token));

          text.append(" ");
          text.append(String.join(" ", splitList));

        }
      );

    return Arrays.stream(SPACES_PATTERN.split(text.toString().trim()))
      .filter(Predicate.not(wordsToIgnore::contains))
      .collect(Collectors.toSet());
  }

  @Override
  protected void check() {

    String lang = info.getResourceString("diagnosticLanguage");
    Map<String, List<Token>> tokensMap = new HashMap<>();
    Map<String, Boolean> checkedWordsForLang = checkedWords.get(lang);

    Set<String> stringsFromTokens = getTokenizedStringsFromTokens(documentContext, tokensMap);

    // build string of unchecked words
    Set<String> uncheckedWords = stringsFromTokens.stream()
      .filter(word -> !checkedWordsForLang.containsKey(word))
      .collect(Collectors.toSet());

    if (uncheckedWords.isEmpty()) {
      fireDiagnosticOnCheckedWordsWithErrors(tokensMap, stringsFromTokens);
      return;
    }

    String uncheckedWordsString = String.join(" ", uncheckedWords);

    JLanguageTool languageTool = acquireLanguageTool(lang);

    List<RuleMatch> matches = Collections.emptyList();
    try {
      matches = languageTool.check(
        uncheckedWordsString,
        true,
        JLanguageTool.ParagraphHandling.ONLYNONPARA
      );
    } catch (IOException e) {
      LOGGER.error(e.getMessage(), e);
    } finally {
      releaseLanguageTool(lang, languageTool);
    }

    // check words and mark matched as checked
    matches.stream()
      .filter(ruleMatch -> !ruleMatch.getSuggestedReplacements().isEmpty())
      .map(ruleMatch -> uncheckedWordsString.substring(ruleMatch.getFromPos(), ruleMatch.getToPos()))
      .forEach((String substring) -> checkedWordsForLang.put(substring, true));

    // mark unmatched words without errors as checked
    uncheckedWords.forEach(word -> checkedWordsForLang.putIfAbsent(word, false));

    fireDiagnosticOnCheckedWordsWithErrors(tokensMap, stringsFromTokens);
  }

  private void fireDiagnosticOnCheckedWordsWithErrors(
    Map<String, List<Token>> tokensMap,
    Set<String> stringsFromTokens
  ) {
    Set<Token> uniqueValues = new HashSet<>();
    String lang = info.getResourceString("diagnosticLanguage");
    Map<String, Boolean> checkedWordsForLang = checkedWords.get(lang);

    stringsFromTokens.stream()
      .filter(word -> checkedWordsForLang.getOrDefault(word, false))
      .forEach((String word) -> {
        List<Token> tokens = tokensMap.get(word);
        if (tokens != null) {
          tokens.stream()
            .filter(uniqueValues::add)
            .forEach(token -> diagnosticStorage.addDiagnostic(token, info.getMessage(word)));
        }
      });
  }

}
