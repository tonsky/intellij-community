package com.intellij.codeInspection.i18n;

import com.intellij.lang.properties.psi.PropertiesFile;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.psi.PsiFile;
import com.intellij.psi.statistics.StatisticsManager;
import com.intellij.psi.statistics.StatisticsInfo;
import gnu.trove.THashMap;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * @author cdr
 */
@State(
  name = "LastSelectedPropertiesFileStore",
  storages = {
    @Storage(
      id ="other",
      file = "$APP_CONFIG$/other.xml"
    )}
)
public class LastSelectedPropertiesFileStore implements PersistentStateComponent<Element> {
  private final Map<String, String> lastSelectedUrls = new THashMap<String, String>();
  private String lastSelectedFileUrl;
  @NonNls private static final String PROPERTIES_FILE_STATISTICS_KEY = "PROPERTIES_FILE";

  public static LastSelectedPropertiesFileStore getInstance() {
    return ServiceManager.getService(LastSelectedPropertiesFileStore.class);
  }

  public String suggestLastSelectedPropertiesFileUrl(PsiFile context) {
    VirtualFile virtualFile = context.getVirtualFile();

    while (virtualFile != null) {
      String contextUrl = virtualFile.getUrl();
      String url = lastSelectedUrls.get(contextUrl);
      if (url != null) {
        return url;
      }
      virtualFile = virtualFile.getParent();
    }
    if (lastSelectedFileUrl != null) {
      VirtualFile lastFile = VirtualFileManager.getInstance().findFileByUrl(lastSelectedFileUrl);
      final ProjectFileIndex fileIndex = ProjectRootManager.getInstance(context.getProject()).getFileIndex();
      if (lastFile != null && ModuleUtil.findModuleForPsiElement(context) == fileIndex.getModuleForFile(lastFile)) {
        return lastSelectedFileUrl;
      }
    }
    return null;
  }

  public static int getUseCount(@NotNull String path) {
    return StatisticsManager.getInstance().getUseCount(new StatisticsInfo(PROPERTIES_FILE_STATISTICS_KEY, path));
  }

  public void saveLastSelectedPropertiesFile(PsiFile context, PropertiesFile file) {
    VirtualFile virtualFile = context.getVirtualFile();
    assert virtualFile != null;
    String contextUrl = virtualFile.getUrl();
    String url = file.getVirtualFile().getUrl();
    lastSelectedUrls.put(contextUrl, url);
    VirtualFile containingDir = virtualFile.getParent();
    lastSelectedUrls.put(containingDir.getUrl(), url);
    lastSelectedFileUrl = url;
    StatisticsManager.getInstance().incUseCount(new StatisticsInfo(PROPERTIES_FILE_STATISTICS_KEY, FileUtil.toSystemDependentName(VfsUtil.urlToPath(url))));
  }

  private void readExternal(@NonNls Element element) {
    lastSelectedUrls.clear();
    List list = element.getChildren("entry");
    for (Object o : list) {
      @NonNls Element child = (Element)o;
      String context = child.getAttributeValue("context");
      String url = child.getAttributeValue("url");
      VirtualFile propFile = VirtualFileManager.getInstance().findFileByUrl(url);
      VirtualFile contextFile = VirtualFileManager.getInstance().findFileByUrl(context);
      if (propFile != null && contextFile != null) {
        lastSelectedUrls.put(context, url);
      }
    }
    lastSelectedFileUrl = element.getAttributeValue("lastSelectedFileUrl");
  }

  private void writeExternal(@NonNls Element element) {
    for (Map.Entry<String, String> entry : lastSelectedUrls.entrySet()) {
      String context = entry.getKey();
      String url = entry.getValue();
      @NonNls Element child = new Element("entry");
      child.setAttribute("context", context);
      child.setAttribute("url", url);
      element.addContent(child);
    }
    if (lastSelectedFileUrl != null) {
      element.setAttribute("lastSelectedFileUrl", lastSelectedFileUrl);
    }
  }

  public Element getState() {
    final Element e = new Element("state");
    writeExternal(e);
    return e;
  }

  public void loadState(Element state) {
    readExternal(state);
  }
}
