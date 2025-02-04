// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vfs.impl.local;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.*;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.ex.VirtualFileManagerEx;
import com.intellij.openapi.vfs.newvfs.ManagingFS;
import com.intellij.openapi.vfs.newvfs.RefreshQueue;
import com.intellij.openapi.vfs.newvfs.VfsImplUtil;
import com.intellij.openapi.vfs.newvfs.impl.FakeVirtualFile;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.PathUtilRt;
import com.intellij.util.ThrowableConsumer;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.io.SafeFileOutputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author Dmitry Avdeev
 */
public abstract class LocalFileSystemBase extends LocalFileSystem {
  protected static final Logger LOG = Logger.getInstance(LocalFileSystemBase.class);

  private static final FileAttributes FAKE_ROOT_ATTRIBUTES =
    new FileAttributes(true, false, false, false, DEFAULT_LENGTH, DEFAULT_TIMESTAMP, false);

  private final List<LocalFileOperationsHandler> myHandlers = new ArrayList<>();

  @Override
  @Nullable
  public VirtualFile findFileByPath(@NotNull String path) {
    return VfsImplUtil.findFileByPath(this, path);
  }

  @Override
  public VirtualFile findFileByPathIfCached(@NotNull String path) {
    return VfsImplUtil.findFileByPathIfCached(this, path);
  }

  @Override
  @Nullable
  public VirtualFile refreshAndFindFileByPath(@NotNull String path) {
    return VfsImplUtil.refreshAndFindFileByPath(this, path);
  }

  @Override
  public VirtualFile findFileByIoFile(@NotNull File file) {
    return findFileByPath(FileUtil.toSystemIndependentName(file.getAbsolutePath()));
  }

  @NotNull
  private static File convertToIOFile(@NotNull VirtualFile file) {
    return convertToPath(file).toFile();
  }

  @NotNull
  static Path convertToPath(@NotNull VirtualFile file) {
    String path = file.getPath();
    if (StringUtil.endsWithChar(path, ':') && path.length() == 2 && SystemInfo.isWindows) {
      path += "/"; // Make 'c:' resolve to a root directory for drive c:, not the current directory on that drive
    }

    return Paths.get(path);
  }

  @NotNull
  private static File convertToIOFileAndCheck(@NotNull VirtualFile file) throws FileNotFoundException {
    File ioFile = convertToIOFile(file);

    if (SystemInfo.isUnix) { // avoid opening fifo files
      FileAttributes attributes = FileSystemUtil.getAttributes(ioFile);
      if (attributes != null && !attributes.isFile()) {
        throw new FileNotFoundException("Not a file: " + ioFile + " (type=" + attributes.type + ')');
      }
    }

    return ioFile;
  }

  @Override
  public boolean exists(@NotNull VirtualFile file) {
    return getAttributes(file) != null;
  }

  @Override
  public long getLength(@NotNull VirtualFile file) {
    FileAttributes attributes = getAttributes(file);
    return attributes != null ? attributes.length : DEFAULT_LENGTH;
  }

  @Override
  public long getTimeStamp(@NotNull VirtualFile file) {
    FileAttributes attributes = getAttributes(file);
    return attributes != null ? attributes.lastModified : DEFAULT_TIMESTAMP;
  }

  @Override
  public boolean isDirectory(@NotNull VirtualFile file) {
    FileAttributes attributes = getAttributes(file);
    return attributes != null && attributes.isDirectory();
  }

  @Override
  public boolean isWritable(@NotNull VirtualFile file) {
    FileAttributes attributes = getAttributes(file);
    return attributes != null && attributes.isWritable();
  }

  @Override
  public boolean isSymLink(@NotNull VirtualFile file) {
    FileAttributes attributes = getAttributes(file);
    return attributes != null && attributes.isSymLink();
  }

  @Override
  public String resolveSymLink(@NotNull VirtualFile file) {
    return FileSystemUtil.resolveSymLink(file.getPath());
  }

  @Override
  @NotNull
  public String[] list(@NotNull VirtualFile file) {
    if (file.getParent() == null) {
      File[] roots = File.listRoots();
      if (roots.length == 1 && roots[0].getName().isEmpty()) {
        String[] list = roots[0].list();
        if (list != null) return list;
        LOG.warn("Root '" + roots[0] + "' has no children - is it readable?");
        return ArrayUtilRt.EMPTY_STRING_ARRAY;
      }
      if (file.getName().isEmpty()) {
        // return drive letter names for the 'fake' root on windows
        String[] names = new String[roots.length];
        for (int i = 0; i < names.length; i++) {
          String name = roots[i].getPath();
          name = StringUtil.trimTrailing(name, File.separatorChar);
          names[i] = name;
        }
        return names;
      }
    }

    String[] names = convertToIOFile(file).list();
    return names == null ? ArrayUtilRt.EMPTY_STRING_ARRAY : names;
  }

  @Override
  @NotNull
  public String getProtocol() {
    return PROTOCOL;
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  @Nullable
  protected String normalize(@NotNull String path) {
    if (path.isEmpty()) {
      try {
        path = new File("").getCanonicalPath();
      }
      catch (IOException e) {
        return path;
      }
    }
    else if (SystemInfo.isWindows) {
      if (path.charAt(0) == '/' && !path.startsWith("//")) {
        path = path.substring(1);  // hack over new File(path).toURI().toURL().getFile()
      }

      try {
        path = FileUtil.resolveShortWindowsName(path);
      }
      catch (IOException e) {
        return null;
      }
    }

    File file = new File(path);
    if (!isAbsoluteFileOrDriveLetter(file)) {
      path = file.getAbsolutePath();
    }

    return FileUtil.normalize(path);
  }

  private static boolean isAbsoluteFileOrDriveLetter(@NotNull File file) {
    String path = file.getPath();
    if (SystemInfo.isWindows && path.length() == 2 && path.charAt(1) == ':') {
      // just drive letter.
      // return true, despite the fact that technically it's not an absolute path
      return true;
    }
    return file.isAbsolute();
  }

  @Override
  public VirtualFile refreshAndFindFileByIoFile(@NotNull File file) {
    String path = FileUtil.toSystemIndependentName(file.getAbsolutePath());
    return refreshAndFindFileByPath(path);
  }

  @Override
  public void refreshIoFiles(@NotNull Iterable<? extends File> files) {
    refreshIoFiles(files, false, false, null);
  }

  @Override
  public void refreshIoFiles(@NotNull Iterable<? extends File> files, boolean async, boolean recursive, @Nullable Runnable onFinish) {
    VirtualFileManagerEx manager = (VirtualFileManagerEx)VirtualFileManager.getInstance();

    Application app = ApplicationManager.getApplication();
    boolean fireCommonRefreshSession = app.isDispatchThread() || app.isWriteAccessAllowed();
    if (fireCommonRefreshSession) manager.fireBeforeRefreshStart(false);

    try {
      List<VirtualFile> filesToRefresh = new ArrayList<>();

      for (File file : files) {
        VirtualFile virtualFile = refreshAndFindFileByIoFile(file);
        if (virtualFile != null) {
          filesToRefresh.add(virtualFile);
        }
      }

      RefreshQueue.getInstance().refresh(async, recursive, onFinish, filesToRefresh);
    }
    finally {
      if (fireCommonRefreshSession) manager.fireAfterRefreshFinish(false);
    }
  }

  @Override
  public void refreshFiles(@NotNull Iterable<? extends VirtualFile> files) {
    refreshFiles(files, false, false, null);
  }

  @Override
  public void refreshFiles(@NotNull Iterable<? extends VirtualFile> files, boolean async, boolean recursive, @Nullable Runnable onFinish) {
    RefreshQueue.getInstance().refresh(async, recursive, onFinish, ContainerUtil.toCollection(files));
  }

  @Override
  public void registerAuxiliaryFileOperationsHandler(@NotNull LocalFileOperationsHandler handler) {
    if (myHandlers.contains(handler)) {
      LOG.error("Handler " + handler + " already registered.");
    }
    myHandlers.add(handler);
  }

  @Override
  public void unregisterAuxiliaryFileOperationsHandler(@NotNull LocalFileOperationsHandler handler) {
    if (!myHandlers.remove(handler)) {
      LOG.error("Handler " + handler + " haven't been registered or already unregistered.");
    }
  }

  private boolean auxDelete(@NotNull VirtualFile file) throws IOException {
    for (LocalFileOperationsHandler handler : myHandlers) {
      if (handler.delete(file)) return true;
    }

    return false;
  }

  private boolean auxMove(@NotNull VirtualFile file, @NotNull VirtualFile toDir) throws IOException {
    for (LocalFileOperationsHandler handler : myHandlers) {
      if (handler.move(file, toDir)) return true;
    }
    return false;
  }

  private boolean auxCopy(@NotNull VirtualFile file, @NotNull VirtualFile toDir, @NotNull String copyName) throws IOException {
    for (LocalFileOperationsHandler handler : myHandlers) {
      File copy = handler.copy(file, toDir, copyName);
      if (copy != null) return true;
    }
    return false;
  }

  private boolean auxRename(@NotNull VirtualFile file, @NotNull String newName) throws IOException {
    for (LocalFileOperationsHandler handler : myHandlers) {
      if (handler.rename(file, newName)) return true;
    }
    return false;
  }

  private boolean auxCreateFile(@NotNull VirtualFile dir, @NotNull String name) throws IOException {
    for (LocalFileOperationsHandler handler : myHandlers) {
      if (handler.createFile(dir, name)) return true;
    }
    return false;
  }

  private boolean auxCreateDirectory(@NotNull VirtualFile dir, @NotNull String name) throws IOException {
    for (LocalFileOperationsHandler handler : myHandlers) {
      if (handler.createDirectory(dir, name)) return true;
    }
    return false;
  }

  private void auxNotifyCompleted(@NotNull ThrowableConsumer<LocalFileOperationsHandler, IOException> consumer) {
    for (LocalFileOperationsHandler handler : myHandlers) {
      handler.afterDone(consumer);
    }
  }

  @Override
  @NotNull
  public VirtualFile createChildDirectory(Object requestor, @NotNull VirtualFile parent, @NotNull String dir) throws IOException {
    if (!isValidName(dir)) {
      throw new IOException(VfsBundle.message("directory.invalid.name.error", dir));
    }

    if (!parent.exists() || !parent.isDirectory()) {
      throw new IOException(VfsBundle.message("vfs.target.not.directory.error", parent.getPath()));
    }
    if (parent.findChild(dir) != null) {
      throw new IOException(VfsBundle.message("vfs.target.already.exists.error", parent.getPath() + "/" + dir));
    }

    File ioParent = convertToIOFile(parent);
    if (!ioParent.isDirectory()) {
      throw new IOException(VfsBundle.message("target.not.directory.error", ioParent.getPath()));
    }

    if (!auxCreateDirectory(parent, dir)) {
      File ioDir = new File(ioParent, dir);
      if (!(ioDir.mkdirs() || ioDir.isDirectory())) {
        throw new IOException(VfsBundle.message("new.directory.failed.error", ioDir.getPath()));
      }
    }

    auxNotifyCompleted(handler -> handler.createDirectory(parent, dir));

    return new FakeVirtualFile(parent, dir);
  }

  @NotNull
  @Override
  public VirtualFile createChildFile(Object requestor, @NotNull VirtualFile parent, @NotNull String file) throws IOException {
    if (!isValidName(file)) {
      throw new IOException(VfsBundle.message("file.invalid.name.error", file));
    }

    if (!parent.exists() || !parent.isDirectory()) {
      throw new IOException(VfsBundle.message("vfs.target.not.directory.error", parent.getPath()));
    }
    if (parent.findChild(file) != null) {
      throw new IOException(VfsBundle.message("vfs.target.already.exists.error", parent.getPath() + "/" + file));
    }

    File ioParent = convertToIOFile(parent);
    if (!ioParent.isDirectory()) {
      throw new IOException(VfsBundle.message("target.not.directory.error", ioParent.getPath()));
    }

    if (!auxCreateFile(parent, file)) {
      File ioFile = new File(ioParent, file);
      if (!FileUtil.createIfDoesntExist(ioFile)) {
        throw new IOException(VfsBundle.message("new.file.failed.error", ioFile.getPath()));
      }
    }

    auxNotifyCompleted(handler -> handler.createFile(parent, file));

    return new FakeVirtualFile(parent, file);
  }

  @Override
  public void deleteFile(Object requestor, @NotNull VirtualFile file) throws IOException {
    if (file.getParent() == null) {
      throw new IOException(VfsBundle.message("cannot.delete.root.directory", file.getPath()));
    }

    if (!auxDelete(file)) {
      File ioFile = convertToIOFile(file);
      if (!FileUtil.delete(ioFile)) {
        throw new IOException(VfsBundle.message("delete.failed.error", ioFile.getPath()));
      }
    }

    auxNotifyCompleted(handler -> handler.delete(file));
  }

  @Override
  public boolean isCaseSensitive() {
    return SystemInfo.isFileSystemCaseSensitive;
  }

  @Override
  public boolean isValidName(@NotNull String name) {
    return PathUtilRt.isValidFileName(name, false);
  }

  @Override
  @NotNull
  public InputStream getInputStream(@NotNull VirtualFile file) throws IOException {
    return new BufferedInputStream(new FileInputStream(convertToIOFileAndCheck(file)));
  }

  @Override
  @NotNull
  public byte[] contentsToByteArray(@NotNull VirtualFile file) throws IOException {
    try (InputStream stream = new FileInputStream(convertToIOFileAndCheck(file))) {
      long l = file.getLength();
      if (l >= FileUtilRt.LARGE_FOR_CONTENT_LOADING) throw new FileTooBigException(file.getPath());
      int length = (int)l;
      if (length < 0) throw new IOException("Invalid file length: " + length + ", " + file);
      // io_util.c#readBytes allocates custom native stack buffer for io operation with malloc if io request > 8K
      // so let's do buffered requests with buffer size 8192 that will use stack allocated buffer
      return loadBytes(length <= 8192 ? stream : new BufferedInputStream(stream), length);
    }
  }

  @NotNull
  private static byte[] loadBytes(@NotNull InputStream stream, int length) throws IOException {
    byte[] bytes = new byte[length];
    int count = 0;
    while (count < length) {
      int n = stream.read(bytes, count, length - count);
      if (n <= 0) break;
      count += n;
    }
    if (count < length) {
      // this may happen with encrypted files, see IDEA-143773
      return Arrays.copyOf(bytes, count);
    }
    return bytes;
  }

  @Override
  @NotNull
  public OutputStream getOutputStream(@NotNull VirtualFile file, Object requestor, long modStamp, long timeStamp) throws IOException {
    File ioFile = convertToIOFileAndCheck(file);
    OutputStream stream = SafeWriteRequestor.shouldUseSafeWrite(requestor) ? new SafeFileOutputStream(ioFile) : new FileOutputStream(ioFile);
    return new BufferedOutputStream(stream) {
      @Override
      public void close() throws IOException {
        super.close();
        if (timeStamp > 0 && ioFile.exists()) {
          if (!ioFile.setLastModified(timeStamp)) {
            LOG.warn("Failed: " + ioFile.getPath() + ", new:" + timeStamp + ", old:" + ioFile.lastModified());
          }
        }
      }
    };
  }

  @Override
  public void moveFile(Object requestor, @NotNull VirtualFile file, @NotNull VirtualFile newParent) throws IOException {
    String name = file.getName();

    if (!file.exists()) {
      throw new IOException(VfsBundle.message("vfs.file.not.exist.error", file.getPath()));
    }
    if (file.getParent() == null) {
      throw new IOException(VfsBundle.message("cannot.rename.root.directory", file.getPath()));
    }
    if (!newParent.exists() || !newParent.isDirectory()) {
      throw new IOException(VfsBundle.message("vfs.target.not.directory.error", newParent.getPath()));
    }
    if (newParent.findChild(name) != null) {
      throw new IOException(VfsBundle.message("vfs.target.already.exists.error", newParent.getPath() + "/" + name));
    }

    File ioFile = convertToIOFile(file);
    if (FileSystemUtil.getAttributes(ioFile) == null) {
      throw new FileNotFoundException(VfsBundle.message("file.not.exist.error", ioFile.getPath()));
    }
    File ioParent = convertToIOFile(newParent);
    if (!ioParent.isDirectory()) {
      throw new IOException(VfsBundle.message("target.not.directory.error", ioParent.getPath()));
    }
    File ioTarget = new File(ioParent, name);
    if (ioTarget.exists()) {
      throw new IOException(VfsBundle.message("target.already.exists.error", ioTarget.getPath()));
    }

    if (!auxMove(file, newParent)) {
      if (!ioFile.renameTo(ioTarget)) {
        throw new IOException(VfsBundle.message("move.failed.error", ioFile.getPath(), ioParent.getPath()));
      }
    }

    auxNotifyCompleted(handler -> handler.move(file, newParent));
  }

  @Override
  public void renameFile(Object requestor, @NotNull VirtualFile file, @NotNull String newName) throws IOException {
    if (!isValidName(newName)) {
      throw new IOException(VfsBundle.message("file.invalid.name.error", newName));
    }

    boolean sameName = !isCaseSensitive() && newName.equalsIgnoreCase(file.getName());

    if (!file.exists()) {
      throw new IOException(VfsBundle.message("vfs.file.not.exist.error", file.getPath()));
    }
    VirtualFile parent = file.getParent();
    if (parent == null) {
      throw new IOException(VfsBundle.message("cannot.rename.root.directory", file.getPath()));
    }
    if (!sameName && parent.findChild(newName) != null) {
      throw new IOException(VfsBundle.message("vfs.target.already.exists.error", parent.getPath() + "/" + newName));
    }

    File ioFile = convertToIOFile(file);
    if (!ioFile.exists()) {
      throw new FileNotFoundException(VfsBundle.message("file.not.exist.error", ioFile.getPath()));
    }
    File ioTarget = new File(convertToIOFile(parent), newName);
    if (!sameName && ioTarget.exists()) {
      throw new IOException(VfsBundle.message("target.already.exists.error", ioTarget.getPath()));
    }

    if (!auxRename(file, newName)) {
      if (!FileUtil.rename(ioFile, newName)) {
        throw new IOException(VfsBundle.message("rename.failed.error", ioFile.getPath(), newName));
      }
    }

    auxNotifyCompleted(handler -> handler.rename(file, newName));
  }

  @NotNull
  @Override
  public VirtualFile copyFile(Object requestor,
                              @NotNull VirtualFile file,
                              @NotNull VirtualFile newParent,
                              @NotNull String copyName) throws IOException {
    if (!isValidName(copyName)) {
      throw new IOException(VfsBundle.message("file.invalid.name.error", copyName));
    }

    if (!file.exists()) {
      throw new IOException(VfsBundle.message("vfs.file.not.exist.error", file.getPath()));
    }
    if (!newParent.exists() || !newParent.isDirectory()) {
      throw new IOException(VfsBundle.message("vfs.target.not.directory.error", newParent.getPath()));
    }
    if (newParent.findChild(copyName) != null) {
      throw new IOException(VfsBundle.message("vfs.target.already.exists.error", newParent.getPath() + "/" + copyName));
    }

    FileAttributes attributes = getAttributes(file);
    if (attributes == null) {
      throw new FileNotFoundException(VfsBundle.message("file.not.exist.error", file.getPath()));
    }
    if (attributes.isSpecial()) {
      throw new FileNotFoundException("Not a file: " + file);
    }
    File ioParent = convertToIOFile(newParent);
    if (!ioParent.isDirectory()) {
      throw new IOException(VfsBundle.message("target.not.directory.error", ioParent.getPath()));
    }
    File ioTarget = new File(ioParent, copyName);
    if (ioTarget.exists()) {
      throw new IOException(VfsBundle.message("target.already.exists.error", ioTarget.getPath()));
    }

    if (!auxCopy(file, newParent, copyName)) {
      try {
        File ioFile = convertToIOFile(file);
        FileUtil.copyFileOrDir(ioFile, ioTarget, attributes.isDirectory());
      }
      catch (IOException e) {
        FileUtil.delete(ioTarget);
        throw e;
      }
    }

    auxNotifyCompleted(handler -> handler.copy(file, newParent, copyName));

    return new FakeVirtualFile(newParent, copyName);
  }

  @Override
  public void setTimeStamp(@NotNull VirtualFile file, long timeStamp) {
    File ioFile = convertToIOFile(file);
    if (ioFile.exists() && !ioFile.setLastModified(timeStamp)) {
      LOG.warn("Failed: " + file.getPath() + ", new:" + timeStamp + ", old:" + ioFile.lastModified());
    }
  }

  @Override
  public void setWritable(@NotNull VirtualFile file, boolean writableFlag) throws IOException {
    String path = FileUtil.toSystemDependentName(file.getPath());
    FileUtil.setReadOnlyAttribute(path, !writableFlag);
    if (FileUtil.canWrite(path) != writableFlag) {
      throw new IOException("Failed to change read-only flag for " + path);
    }
  }

  private static final String[] ourRootPaths;
  static {
    //noinspection SpellCheckingInspection
    List<String> roots = StringUtil.split(System.getProperty("idea.persistentfs.roots", ""), File.pathSeparator);
    Collections.sort(roots, (o1, o2) -> o2.length() - o1.length());  // longest first
    ourRootPaths = ArrayUtil.toStringArray(roots);
  }

  @NotNull
  @Override
  protected String extractRootPath(@NotNull String path) {
    if (path.isEmpty()) {
      try {
        path = new File("").getCanonicalPath();
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    for (String customRootPath : ourRootPaths) {
      if (path.startsWith(customRootPath)) return customRootPath;
    }

    if (SystemInfo.isWindows) {
      if (path.length() >= 2 && path.charAt(1) == ':') {
        // Drive letter
        return StringUtil.toUpperCase(path.substring(0, 2));
      }

      if (path.startsWith("//") || path.startsWith("\\\\")) {
        // UNC. Must skip exactly two path elements like [\\ServerName\ShareName]\pathOnShare\file.txt
        // Root path is in square brackets here.

        int slashCount = 0;
        int idx;
        for (idx = 2; idx < path.length() && slashCount < 2; idx++) {
          char c = path.charAt(idx);
          if (c == '\\' || c == '/') {
            slashCount++;
            idx--;
          }
        }

        return path.substring(0, idx);
      }

      return "";
    }

    return StringUtil.startsWithChar(path, '/') ? "/" : "";
  }

  @Override
  public int getRank() {
    return 1;
  }

  @Override
  public boolean markNewFilesAsDirty() {
    return true;
  }

  @NotNull
  @Override
  public String getCanonicallyCasedName(@NotNull VirtualFile file) {
    if (isCaseSensitive()) {
      return super.getCanonicallyCasedName(file);
    }

    String originalFileName = file.getName();
    long t = LOG.isTraceEnabled() ? System.nanoTime() : 0;
    try {
      File ioFile = convertToIOFile(file);

      File canonicalFile = ioFile.getCanonicalFile();
      String canonicalFileName = canonicalFile.getName();
      if (!SystemInfo.isUnix) {
        return canonicalFileName;
      }

      // linux & mac support symbolic links
      // unfortunately canonical file resolves sym links
      // so its name may differ from name of origin file
      //
      // Here FS is case sensitive, so let's check that original and
      // canonical file names are equal if we ignore name case
      if (canonicalFileName.compareToIgnoreCase(originalFileName) == 0) {
        // p.s. this should cover most cases related to not symbolic links
        return canonicalFileName;
      }

      // Ok, names are not equal. Let's try to find corresponding file name
      // among original file parent directory
      File parentFile = ioFile.getParentFile();
      if (parentFile != null) {
        // I hope ls works fast on Unix
        String[] canonicalFileNames = parentFile.list();
        if (canonicalFileNames != null) {
          for (String name : canonicalFileNames) {
            // if names are equals
            if (name.compareToIgnoreCase(originalFileName) == 0) {
              return name;
            }
          }
        }
      }
      // No luck. So ein mist!
      // Ok, garbage in, garbage out. We may return original or canonical name
      // no difference. Let's return canonical name just to preserve previous
      // behaviour of this code.
      return canonicalFileName;
    }
    catch (IOException e) {
      return originalFileName;
    }
    finally {
      if (t != 0) {
        t = (System.nanoTime() - t) / 1000;
        LOG.trace("getCanonicallyCasedName(" + file + "): " + t + " mks");
      }
    }
  }

  @Override
  public FileAttributes getAttributes(@NotNull VirtualFile file) {
    String path = normalize(file.getPath());
    if (path == null) return null;
    if (file.getParent() == null && path.startsWith("//")) {
      return FAKE_ROOT_ATTRIBUTES;  // fake Windows roots
    }
    return FileSystemUtil.getAttributes(FileUtil.toSystemDependentName(path));
  }

  @Override
  public void refresh(boolean asynchronous) {
    RefreshQueue.getInstance().refresh(asynchronous, true, null, ManagingFS.getInstance().getRoots(this));
  }

  @Override
  public boolean hasChildren(@NotNull VirtualFile file) {
    if (file.getParent() == null) {
      // assume roots always have children
      return true;
    }
    // make sure to not load all children
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(file.getPath()))) {
      return stream.iterator().hasNext();
    }
    catch (InvalidPathException | IOException | SecurityException e) {
      return true;
    }
  }
}