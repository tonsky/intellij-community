// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.editor.impl;

import com.intellij.openapi.editor.impl.view.FontLayoutService;
import com.intellij.openapi.util.SystemInfo;
import gnu.trove.TIntHashSet;
import org.intellij.lang.annotations.JdkConstants;
import org.jetbrains.annotations.NotNull;
import sun.font.CompositeGlyphMapper;
import sun.font.FontDesignMetrics;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

/**
 * @author max
 */
public class FontInfo {
  private static final FontRenderContext DEFAULT_CONTEXT = new FontRenderContext(null, false, false);
  private static final Font DUMMY_FONT = new Font(null);

  private final Font myFont;
  private final int mySize;
  @JdkConstants.FontStyle private final int myStyle;
  private final TIntHashSet mySafeCharacters = new TIntHashSet();
  private final FontRenderContext myContext;
  private FontMetrics myFontMetrics = null;

  /**
   * @deprecated Use {@link #FontInfo(String, int, int, boolean, FontRenderContext)} instead.
   */
  @Deprecated
  public FontInfo(final String familyName, final int size, @JdkConstants.FontStyle int style) {
    this(familyName, size, style, Collections.emptyMap(), Collections.emptyMap(), null);
  }

  /**
   * @deprecated Use {@link #FontInfo(String, int, int, boolean, FontRenderContext)} instead.
   */
  @Deprecated
  public FontInfo(final String familyName, final int size, @JdkConstants.FontStyle int style, Map<String, Integer> features, Map<String, Float> variations) {
    this(familyName, size, style, features, variations, null);
  }

  private static Class featureClass = null;
  private static Method featureMethodFromString = null;
  private static Method fontMethodDeriveFeature = null;
  
  private static Class variationClass = null;
  private static Method variationMethodFromString = null;
  private static Method fontMethodDeriveVariation = null;
  
  static {
    try {
      featureClass = Class.forName("java.awt.font.FontFeature");
      featureMethodFromString = featureClass.getDeclaredMethod("fromString", String.class, Integer.TYPE);
      fontMethodDeriveFeature = Font.class.getDeclaredMethod("deriveFont", featureClass);
      
      variationClass = Class.forName("java.awt.font.FontVariation");
      variationMethodFromString = variationClass.getDeclaredMethod("fromString", String.class, Float.TYPE);
      fontMethodDeriveVariation = Font.class.getDeclaredMethod("deriveFont", variationClass);
    } catch (ClassNotFoundException | NoSuchMethodException e) {
      // not JBR
    }
  }
    
  private static Font deriveFeature(Font font, String feature, int value) {
    try {
      return (Font) fontMethodDeriveFeature.invoke(font, featureMethodFromString.invoke(null, feature, value));
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }
  
  private static Font deriveVariation(Font font, String variation, float value) {
    try {
      return (Font) fontMethodDeriveVariation.invoke(font, variationMethodFromString.invoke(null, variation, value));
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * To get valid font metrics from this {@link FontInfo} instance, pass valid {@link FontRenderContext} here as a parameter.
   */
  public FontInfo(final String familyName, final int size, @JdkConstants.FontStyle int style, Map<String, Integer> features, Map<String, Float> variations,
                  FontRenderContext fontRenderContext) {
    mySize = size;
    myStyle = style;
    Font font = new Font(familyName, style, size);
    
    if (featureClass != null && variationClass != null) {
      for (Map.Entry<String, Integer> entry: features.entrySet()) {
        font = deriveFeature(font, entry.getKey(), entry.getValue());
      }
      for (Map.Entry<String, Float> entry: variations.entrySet()) {
        font = deriveVariation(font, entry.getKey(), entry.getValue());
      }
      myFont = font;
    } else if (features.get("liga") == 1) {
        myFont = font.deriveFont(Collections.singletonMap(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON));
    } else {
        myFont = font;
    }

    myContext = fontRenderContext;
  }

  public boolean canDisplay(int codePoint) {
    try {
      if (codePoint < 128) return true;
      if (mySafeCharacters.contains(codePoint)) return true;
      if (canDisplay(myFont, codePoint, false)) {
        mySafeCharacters.add(codePoint);
        return true;
      }
      return false;
    }
    catch (Exception e) {
      // JRE has problems working with the font. Just skip.
      return false;
    }
  }

  public static boolean canDisplay(@NotNull Font font, int codePoint, boolean disableFontFallback) {
    if (!Character.isValidCodePoint(codePoint)) return false;
    if (disableFontFallback && SystemInfo.isMac) {
      int glyphCode = font.createGlyphVector(DEFAULT_CONTEXT, new String(new int[]{codePoint}, 0, 1)).getGlyphCode(0);
      return (glyphCode & CompositeGlyphMapper.GLYPHMASK) != 0 && (glyphCode & CompositeGlyphMapper.SLOTMASK) == 0;
    }
    else {
      return font.canDisplay(codePoint);
    }
  }

  public Font getFont() {
    return myFont;
  }

  public int charWidth(int codePoint) {
    final FontMetrics metrics = fontMetrics();
    return FontLayoutService.getInstance().charWidth(metrics, codePoint);
  }

  public float charWidth2D(int codePoint) {
    FontMetrics metrics = fontMetrics();
    return FontLayoutService.getInstance().charWidth2D(metrics, codePoint);
  }

  public synchronized FontMetrics fontMetrics() {
    if (myFontMetrics == null) {
      myFontMetrics = getFontMetrics(myFont, myContext == null ? getFontRenderContext(null) : myContext);
    }
    return myFontMetrics;
  }

  @NotNull
  public static FontMetrics getFontMetrics(@NotNull Font font, @NotNull FontRenderContext fontRenderContext) {
    return FontDesignMetrics.getMetrics(font, fontRenderContext);
  }

  public static FontRenderContext getFontRenderContext(Component component) {
    if (component == null) {
        return DEFAULT_CONTEXT;
    }
    return component.getFontMetrics(DUMMY_FONT).getFontRenderContext();
  }

  public int getSize() {
    return mySize;
  }

  @JdkConstants.FontStyle
  public int getStyle() {
    return myStyle;
  }

  public FontRenderContext getFontRenderContext() {
    return myContext;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    FontInfo fontInfo = (FontInfo)o;

    if (!myFont.equals(fontInfo.myFont)) return false;

    return true;
  }

  @Override
  public int hashCode() {
    return myFont.hashCode();
  }
}
