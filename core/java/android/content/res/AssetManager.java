/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content.res;

import android.annotation.AnyRes;
import android.annotation.ArrayRes;
import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.StyleRes;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration.NativeConfig;
import android.os.ParcelFileDescriptor;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import libcore.io.IoUtils;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Provides access to an application's raw asset files; see {@link Resources}
 * for the way most applications will want to retrieve their resource data.
 * This class presents a lower-level API that allows you to open and read raw
 * files that have been bundled with the application as a simple stream of
 * bytes.
 */
public final class AssetManager implements AutoCloseable {
    private static final String TAG = "AssetManager";
    private static final boolean DEBUG_REFS = false;

    private static final String FRAMEWORK_APK_PATH = "/system/framework/framework-res.apk";

    private static final Object sSync = new Object();

    private static final ApkAssets[] sEmptyApkAssets = new ApkAssets[0];

    // Not private for LayoutLib's BridgeAssetManager.
    @GuardedBy("sSync") static AssetManager sSystem = null;

    @GuardedBy("sSync") private static ApkAssets[] sSystemApkAssets = new ApkAssets[0];
    @GuardedBy("sSync") private static ArraySet<ApkAssets> sSystemApkAssetsSet;

    /**
     * Mode for {@link #open(String, int)}: no specific information about how
     * data will be accessed.
     */
    public static final int ACCESS_UNKNOWN = 0;
    /**
     * Mode for {@link #open(String, int)}: Read chunks, and seek forward and
     * backward.
     */
    public static final int ACCESS_RANDOM = 1;
    /**
     * Mode for {@link #open(String, int)}: Read sequentially, with an
     * occasional forward seek.
     */
    public static final int ACCESS_STREAMING = 2;
    /**
     * Mode for {@link #open(String, int)}: Attempt to load contents into
     * memory, for fast small reads.
     */
    public static final int ACCESS_BUFFER = 3;

    @GuardedBy("this") private final TypedValue mValue = new TypedValue();
    @GuardedBy("this") private final long[] mOffsets = new long[2];

    // Pointer to native implementation, stuffed inside a long.
    @GuardedBy("this") private long mObject;

    // The loaded asset paths.
    @GuardedBy("this") private ApkAssets[] mApkAssets;

    // Debug/reference counting implementation.
    @GuardedBy("this") private boolean mOpen = true;
    @GuardedBy("this") private int mNumRefs = 1;
    @GuardedBy("this") private HashMap<Long, RuntimeException> mRefStacks;

    /**
     * A Builder class that helps create an AssetManager with only a single invocation of
     * {@link AssetManager#setApkAssets(ApkAssets[], boolean)}. Without using this builder,
     * AssetManager must ensure there are system ApkAssets loaded at all times, which when combined
     * with the user's call to add additional ApkAssets, results in multiple calls to
     * {@link AssetManager#setApkAssets(ApkAssets[], boolean)}.
     * @hide
     */
    public static class Builder {
        private ArrayList<ApkAssets> mUserApkAssets = new ArrayList<>();

        public Builder addApkAssets(ApkAssets apkAssets) {
            mUserApkAssets.add(apkAssets);
            return this;
        }

        public AssetManager build() {
            // Retrieving the system ApkAssets forces their creation as well.
            final ApkAssets[] systemApkAssets = getSystem().getApkAssets();

            final int totalApkAssetCount = systemApkAssets.length + mUserApkAssets.size();
            final ApkAssets[] apkAssets = new ApkAssets[totalApkAssetCount];

            System.arraycopy(systemApkAssets, 0, apkAssets, 0, systemApkAssets.length);

            final int userApkAssetCount = mUserApkAssets.size();
            for (int i = 0; i < userApkAssetCount; i++) {
                apkAssets[i + systemApkAssets.length] = mUserApkAssets.get(i);
            }

            // Calling this constructor prevents creation of system ApkAssets, which we took care
            // of in this Builder.
            final AssetManager assetManager = new AssetManager(false /*sentinel*/);
            assetManager.mApkAssets = apkAssets;
            AssetManager.nativeSetApkAssets(assetManager.mObject, apkAssets,
                    false /*invalidateCaches*/);
            return assetManager;
        }
    }

    /**
     * Create a new AssetManager containing only the basic system assets.
     * Applications will not generally use this method, instead retrieving the
     * appropriate asset manager with {@link Resources#getAssets}.    Not for
     * use by applications.
     * @hide
     */
    public AssetManager() {
        final ApkAssets[] assets;
        synchronized (sSync) {
            createSystemAssetsInZygoteLocked();
            assets = sSystemApkAssets;
        }

        mObject = nativeCreate();
        if (DEBUG_REFS) {
            mNumRefs = 0;
            incRefsLocked(hashCode());
        }

        // Always set the framework resources.
        setApkAssets(assets, false /*invalidateCaches*/);
    }

    /**
     * Private constructor that doesn't call ensureSystemAssets.
     * Used for the creation of system assets.
     */
    @SuppressWarnings("unused")
    private AssetManager(boolean sentinel) {
        mObject = nativeCreate();
        if (DEBUG_REFS) {
            mNumRefs = 0;
            incRefsLocked(hashCode());
        }
    }

    /**
     * This must be called from Zygote so that system assets are shared by all applications.
     */
    @GuardedBy("sSync")
    private static void createSystemAssetsInZygoteLocked() {
        if (sSystem != null) {
            return;
        }

        // Make sure that all IDMAPs are up to date.
        nativeVerifySystemIdmaps();

        try {
            final ArrayList<ApkAssets> apkAssets = new ArrayList<>();
            apkAssets.add(ApkAssets.loadFromPath(FRAMEWORK_APK_PATH, true /*system*/));
            loadStaticRuntimeOverlays(apkAssets);

            sSystemApkAssetsSet = new ArraySet<>(apkAssets);
            sSystemApkAssets = apkAssets.toArray(new ApkAssets[apkAssets.size()]);
            sSystem = new AssetManager(true /*sentinel*/);
            sSystem.setApkAssets(sSystemApkAssets, false /*invalidateCaches*/);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create system AssetManager", e);
        }
    }

    /**
     * Loads the static runtime overlays declared in /data/resource-cache/overlays.list.
     * Throws an exception if the file is corrupt or if loading the APKs referenced by the file
     * fails. Returns quietly if the overlays.list file doesn't exist.
     * @param outApkAssets The list to fill with the loaded ApkAssets.
     */
    private static void loadStaticRuntimeOverlays(ArrayList<ApkAssets> outApkAssets)
            throws IOException {
        final FileInputStream fis;
        try {
            fis = new FileInputStream("/data/resource-cache/overlays.list");
        } catch (FileNotFoundException e) {
            // We might not have any overlays, this is fine. We catch here since ApkAssets
            // loading can also fail with the same exception, which we would want to propagate.
            Log.i(TAG, "no overlays.list file found");
            return;
        }

        try {
            // Acquire a lock so that any idmap scanning doesn't impact the current set.
            // The order of this try-with-resources block matters. We must release the lock, and
            // then close the file streams when exiting the block.
            try (final BufferedReader br = new BufferedReader(new InputStreamReader(fis));
                 final FileLock flock = fis.getChannel().lock(0, Long.MAX_VALUE, true /*shared*/)) {
                for (String line; (line = br.readLine()) != null; ) {
                    final String idmapPath = line.split(" ")[1];
                    outApkAssets.add(ApkAssets.loadOverlayFromPath(idmapPath, true /*system*/));
                }
            }
        } finally {
            // When BufferedReader is closed above, FileInputStream is closed as well. But let's be
            // paranoid.
            IoUtils.closeQuietly(fis);
        }
    }

    /**
     * Return a global shared asset manager that provides access to only
     * system assets (no application assets).
     * @hide
     */
    public static AssetManager getSystem() {
        synchronized (sSync) {
            createSystemAssetsInZygoteLocked();
            return sSystem;
        }
    }

    /**
     * Close this asset manager.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (!mOpen) {
                return;
            }

            mOpen = false;
            decRefsLocked(hashCode());
        }
    }

    /**
     * Changes the asset paths in this AssetManager. This replaces the {@link #addAssetPath(String)}
     * family of methods.
     *
     * @param apkAssets The new set of paths.
     * @param invalidateCaches Whether to invalidate any caches. This should almost always be true.
     *                         Set this to false if you are appending new resources
     *                         (not new configurations).
     * @hide
     */
    public void setApkAssets(@NonNull ApkAssets[] apkAssets, boolean invalidateCaches) {
        Preconditions.checkNotNull(apkAssets, "apkAssets");

        ApkAssets[] newApkAssets = new ApkAssets[sSystemApkAssets.length + apkAssets.length];

        // Copy the system assets first.
        System.arraycopy(sSystemApkAssets, 0, newApkAssets, 0, sSystemApkAssets.length);

        // Copy the given ApkAssets if they are not already in the system list.
        int newLength = sSystemApkAssets.length;
        for (ApkAssets apkAsset : apkAssets) {
            if (!sSystemApkAssetsSet.contains(apkAsset)) {
                newApkAssets[newLength++] = apkAsset;
            }
        }

        // Truncate if necessary.
        if (newLength != newApkAssets.length) {
            newApkAssets = Arrays.copyOf(newApkAssets, newLength);
        }

        synchronized (this) {
            ensureOpenLocked();
            mApkAssets = newApkAssets;
            nativeSetApkAssets(mObject, mApkAssets, invalidateCaches);
            if (invalidateCaches) {
                // Invalidate all caches.
                invalidateCachesLocked(-1);
            }
        }
    }

    /**
     * Invalidates the caches in this AssetManager according to the bitmask `diff`.
     *
     * @param diff The bitmask of changes generated by {@link Configuration#diff(Configuration)}.
     * @see ActivityInfo.Config
     */
    private void invalidateCachesLocked(int diff) {
        // TODO(adamlesinski): Currently there are no caches to invalidate in Java code.
    }

    /**
     * Returns the set of ApkAssets loaded by this AssetManager. If the AssetManager is closed, this
     * returns a 0-length array.
     * @hide
     */
    public @NonNull ApkAssets[] getApkAssets() {
        synchronized (this) {
            if (mOpen) {
                return mApkAssets;
            }
        }
        return sEmptyApkAssets;
    }

    /**
     * Returns a cookie for use with the other APIs of AssetManager.
     * @return 0 if the path was not found, otherwise a positive integer cookie representing
     * this path in the AssetManager.
     * @hide
     */
    public int findCookieForPath(@NonNull String path) {
        Preconditions.checkNotNull(path, "path");
        synchronized (this) {
            ensureValidLocked();
            final int count = mApkAssets.length;
            for (int i = 0; i < count; i++) {
                if (path.equals(mApkAssets[i].getAssetPath())) {
                    return i + 1;
                }
            }
        }
        return 0;
    }

    /**
     * @deprecated Use {@link #setApkAssets(ApkAssets[], boolean)}
     * @hide
     */
    @Deprecated
    public int addAssetPath(String path) {
        return addAssetPathInternal(path, false /*overlay*/, false /*appAsLib*/);
    }

    /**
     * @deprecated Use {@link #setApkAssets(ApkAssets[], boolean)}
     * @hide
     */
    @Deprecated
    public int addAssetPathAsSharedLibrary(String path) {
        return addAssetPathInternal(path, false /*overlay*/, true /*appAsLib*/);
    }

    /**
     * @deprecated Use {@link #setApkAssets(ApkAssets[], boolean)}
     * @hide
     */
    @Deprecated
    public int addOverlayPath(String path) {
        return addAssetPathInternal(path, true /*overlay*/, false /*appAsLib*/);
    }

    private int addAssetPathInternal(String path, boolean overlay, boolean appAsLib) {
        Preconditions.checkNotNull(path, "path");
        synchronized (this) {
            ensureOpenLocked();
            final int count = mApkAssets.length;

            // See if we already have it loaded.
            for (int i = 0; i < count; i++) {
                if (mApkAssets[i].getAssetPath().equals(path)) {
                    return i + 1;
                }
            }

            final ApkAssets assets;
            try {
                if (overlay) {
                    // TODO(b/70343104): This hardcoded path will be removed once
                    // addAssetPathInternal is deleted.
                    final String idmapPath = "/data/resource-cache/"
                            + path.substring(1).replace('/', '@')
                            + "@idmap";
                    assets = ApkAssets.loadOverlayFromPath(idmapPath, false /*system*/);
                } else {
                    assets = ApkAssets.loadFromPath(path, false /*system*/, appAsLib);
                }
            } catch (IOException e) {
                return 0;
            }

            mApkAssets = Arrays.copyOf(mApkAssets, count + 1);
            mApkAssets[count] = assets;
            nativeSetApkAssets(mObject, mApkAssets, true);
            invalidateCachesLocked(-1);
            return count + 1;
        }
    }

    /**
     * Ensures that the native implementation has not been destroyed.
     * The AssetManager may have been closed, but references to it still exist
     * and therefore the native implementation is not destroyed.
     */
    @GuardedBy("this")
    private void ensureValidLocked() {
        if (mObject == 0) {
            throw new RuntimeException("AssetManager has been destroyed");
        }
    }

    /**
     * Ensures that the AssetManager has not been explicitly closed. If this method passes,
     * then this implies that ensureValidLocked() also passes.
     */
    @GuardedBy("this")
    private void ensureOpenLocked() {
        // If mOpen is true, this implies that mObject != 0.
        if (!mOpen) {
            throw new RuntimeException("AssetManager has been closed");
        }
    }

    /**
     * Populates {@code outValue} with the data associated a particular
     * resource identifier for the current configuration.
     *
     * @param resId the resource identifier to load
     * @param densityDpi the density bucket for which to load the resource
     * @param outValue the typed value in which to put the data
     * @param resolveRefs {@code true} to resolve references, {@code false}
     *                    to leave them unresolved
     * @return {@code true} if the data was loaded into {@code outValue},
     *         {@code false} otherwise
     */
    boolean getResourceValue(@AnyRes int resId, int densityDpi, @NonNull TypedValue outValue,
            boolean resolveRefs) {
        Preconditions.checkNotNull(outValue, "outValue");
        synchronized (this) {
            ensureValidLocked();
            final int cookie = nativeGetResourceValue(
                    mObject, resId, (short) densityDpi, outValue, resolveRefs);
            if (cookie <= 0) {
                return false;
            }

            // Convert the changing configurations flags populated by native code.
            outValue.changingConfigurations = ActivityInfo.activityInfoConfigNativeToJava(
                    outValue.changingConfigurations);

            if (outValue.type == TypedValue.TYPE_STRING) {
                outValue.string = mApkAssets[cookie - 1].getStringFromPool(outValue.data);
            }
            return true;
        }
    }

    /**
     * Retrieves the string value associated with a particular resource
     * identifier for the current configuration.
     *
     * @param resId the resource identifier to load
     * @return the string value, or {@code null}
     */
    @Nullable CharSequence getResourceText(@StringRes int resId) {
        synchronized (this) {
            final TypedValue outValue = mValue;
            if (getResourceValue(resId, 0, outValue, true)) {
                return outValue.coerceToString();
            }
            return null;
        }
    }

    /**
     * Retrieves the string value associated with a particular resource
     * identifier for the current configuration.
     *
     * @param resId the resource identifier to load
     * @param bagEntryId the index into the bag to load
     * @return the string value, or {@code null}
     */
    @Nullable CharSequence getResourceBagText(@StringRes int resId, int bagEntryId) {
        synchronized (this) {
            ensureValidLocked();
            final TypedValue outValue = mValue;
            final int cookie = nativeGetResourceBagValue(mObject, resId, bagEntryId, outValue);
            if (cookie <= 0) {
                return null;
            }

            // Convert the changing configurations flags populated by native code.
            outValue.changingConfigurations = ActivityInfo.activityInfoConfigNativeToJava(
                    outValue.changingConfigurations);

            if (outValue.type == TypedValue.TYPE_STRING) {
                return mApkAssets[cookie - 1].getStringFromPool(outValue.data);
            }
            return outValue.coerceToString();
        }
    }

    int getResourceArraySize(@ArrayRes int resId) {
        synchronized (this) {
            ensureValidLocked();
            return nativeGetResourceArraySize(mObject, resId);
        }
    }

    /**
     * Populates `outData` with array elements of `resId`. `outData` is normally
     * used with
     * {@link TypedArray}.
     *
     * Each logical element in `outData` is {@link TypedArray#STYLE_NUM_ENTRIES}
     * long,
     * with the indices of the data representing the type, value, asset cookie,
     * resource ID,
     * configuration change mask, and density of the element.
     *
     * @param resId The resource ID of an array resource.
     * @param outData The array to populate with data.
     * @return The length of the array.
     *
     * @see TypedArray#STYLE_TYPE
     * @see TypedArray#STYLE_DATA
     * @see TypedArray#STYLE_ASSET_COOKIE
     * @see TypedArray#STYLE_RESOURCE_ID
     * @see TypedArray#STYLE_CHANGING_CONFIGURATIONS
     * @see TypedArray#STYLE_DENSITY
     */
    int getResourceArray(@ArrayRes int resId, @NonNull int[] outData) {
        Preconditions.checkNotNull(outData, "outData");
        synchronized (this) {
            ensureValidLocked();
            return nativeGetResourceArray(mObject, resId, outData);
        }
    }

    /**
     * Retrieves the string array associated with a particular resource
     * identifier for the current configuration.
     *
     * @param resId the resource identifier of the string array
     * @return the string array, or {@code null}
     */
    @Nullable String[] getResourceStringArray(@ArrayRes int resId) {
        synchronized (this) {
            ensureValidLocked();
            return nativeGetResourceStringArray(mObject, resId);
        }
    }

    /**
     * Retrieve the text array associated with a particular resource
     * identifier.
     *
     * @param resId the resource id of the string array
     */
    @Nullable CharSequence[] getResourceTextArray(@ArrayRes int resId) {
        synchronized (this) {
            ensureValidLocked();
            final int[] rawInfoArray = nativeGetResourceStringArrayInfo(mObject, resId);
            if (rawInfoArray == null) {
                return null;
            }

            final int rawInfoArrayLen = rawInfoArray.length;
            final int infoArrayLen = rawInfoArrayLen / 2;
            final CharSequence[] retArray = new CharSequence[infoArrayLen];
            for (int i = 0, j = 0; i < rawInfoArrayLen; i = i + 2, j++) {
                int cookie = rawInfoArray[i];
                int index = rawInfoArray[i + 1];
                retArray[j] = (index >= 0 && cookie > 0)
                        ? mApkAssets[cookie - 1].getStringFromPool(index) : null;
            }
            return retArray;
        }
    }

    @Nullable int[] getResourceIntArray(@ArrayRes int resId) {
        synchronized (this) {
            ensureValidLocked();
            return nativeGetResourceIntArray(mObject, resId);
        }
    }

    /**
     * Get the attributes for a style resource. These are the &lt;item&gt;
     * elements in
     * a &lt;style&gt; resource.
     * @param resId The resource ID of the style
     * @return An array of attribute IDs.
     */
    @AttrRes int[] getStyleAttributes(@StyleRes int resId) {
        synchronized (this) {
            ensureValidLocked();
            return nativeGetStyleAttributes(mObject, resId);
        }
    }

    /**
     * Populates {@code outValue} with the data associated with a particular
     * resource identifier for the current configuration. Resolves theme
     * attributes against the specified theme.
     *
     * @param theme the native pointer of the theme
     * @param resId the resource identifier to load
     * @param outValue the typed value in which to put the data
     * @param resolveRefs {@code true} to resolve references, {@code false}
     *                    to leave them unresolved
     * @return {@code true} if the data was loaded into {@code outValue},
     *         {@code false} otherwise
     */
    boolean getThemeValue(long theme, @AnyRes int resId, @NonNull TypedValue outValue,
            boolean resolveRefs) {
        Preconditions.checkNotNull(outValue, "outValue");
        synchronized (this) {
            ensureValidLocked();
            final int cookie = nativeThemeGetAttributeValue(mObject, theme, resId, outValue,
                    resolveRefs);
            if (cookie <= 0) {
                return false;
            }

            // Convert the changing configurations flags populated by native code.
            outValue.changingConfigurations = ActivityInfo.activityInfoConfigNativeToJava(
                    outValue.changingConfigurations);

            if (outValue.type == TypedValue.TYPE_STRING) {
                outValue.string = mApkAssets[cookie - 1].getStringFromPool(outValue.data);
            }
            return true;
        }
    }

    void dumpTheme(long theme, int priority, String tag, String prefix) {
        synchronized (this) {
            ensureValidLocked();
            nativeThemeDump(mObject, theme, priority, tag, prefix);
        }
    }

    @Nullable String getResourceName(@AnyRes int resId) {
        synchronized (this) {
            ensureValidLocked();
            return nativeGetResourceName(mObject, resId);
        }
    }

    @Nullable String getResourcePackageName(@AnyRes int resId) {
        synchronized (this) {
            ensureValidLocked();
            return nativeGetResourcePackageName(mObject, resId);
        }
    }

    @Nullable String getResourceTypeName(@AnyRes int resId) {
        synchronized (this) {
            ensureValidLocked();
            return nativeGetResourceTypeName(mObject, resId);
        }
    }

    @Nullable String getResourceEntryName(@AnyRes int resId) {
        synchronized (this) {
            ensureValidLocked();
            return nativeGetResourceEntryName(mObject, resId);
        }
    }

    @AnyRes int getResourceIdentifier(@NonNull String name, @Nullable String defType,
            @Nullable String defPackage) {
        synchronized (this) {
            ensureValidLocked();
            // name is checked in JNI.
            return nativeGetResourceIdentifier(mObject, name, defType, defPackage);
        }
    }

    CharSequence getPooledStringForCookie(int cookie, int id) {
        // Cookies map to ApkAssets starting at 1.
        return getApkAssets()[cookie - 1].getStringFromPool(id);
    }

    /**
     * Open an asset using ACCESS_STREAMING mode.  This provides access to
     * files that have been bundled with an application as assets -- that is,
     * files placed in to the "assets" directory.
     * 
     * @param fileName The name of the asset to open.  This name can be hierarchical.
     * 
     * @see #open(String, int)
     * @see #list
     */
    public @NonNull InputStream open(@NonNull String fileName) throws IOException {
        return open(fileName, ACCESS_STREAMING);
    }

    /**
     * Open an asset using an explicit access mode, returning an InputStream to
     * read its contents.  This provides access to files that have been bundled
     * with an application as assets -- that is, files placed in to the
     * "assets" directory.
     * 
     * @param fileName The name of the asset to open.  This name can be hierarchical.
     * @param accessMode Desired access mode for retrieving the data.
     * 
     * @see #ACCESS_UNKNOWN
     * @see #ACCESS_STREAMING
     * @see #ACCESS_RANDOM
     * @see #ACCESS_BUFFER
     * @see #open(String)
     * @see #list
     */
    public @NonNull InputStream open(@NonNull String fileName, int accessMode) throws IOException {
        Preconditions.checkNotNull(fileName, "fileName");
        synchronized (this) {
            ensureOpenLocked();
            final long asset = nativeOpenAsset(mObject, fileName, accessMode);
            if (asset == 0) {
                throw new FileNotFoundException("Asset file: " + fileName);
            }
            final AssetInputStream assetInputStream = new AssetInputStream(asset);
            incRefsLocked(assetInputStream.hashCode());
            return assetInputStream;
        }
    }

    /**
     * Open an uncompressed asset by mmapping it and returning an {@link AssetFileDescriptor}.
     * This provides access to files that have been bundled with an application as assets -- that
     * is, files placed in to the "assets" directory.
     *
     * The asset must be uncompressed, or an exception will be thrown.
     *
     * @param fileName The name of the asset to open.  This name can be hierarchical.
     * @return An open AssetFileDescriptor.
     */
    public @NonNull AssetFileDescriptor openFd(@NonNull String fileName) throws IOException {
        Preconditions.checkNotNull(fileName, "fileName");
        synchronized (this) {
            ensureOpenLocked();
            final ParcelFileDescriptor pfd = nativeOpenAssetFd(mObject, fileName, mOffsets);
            if (pfd == null) {
                throw new FileNotFoundException("Asset file: " + fileName);
            }
            return new AssetFileDescriptor(pfd, mOffsets[0], mOffsets[1]);
        }
    }

    /**
     * Return a String array of all the assets at the given path.
     * 
     * @param path A relative path within the assets, i.e., "docs/home.html".
     * 
     * @return String[] Array of strings, one for each asset.  These file
     *         names are relative to 'path'.  You can open the file by
     *         concatenating 'path' and a name in the returned string (via
     *         File) and passing that to open().
     * 
     * @see #open
     */
    public @Nullable String[] list(@NonNull String path) throws IOException {
        Preconditions.checkNotNull(path, "path");
        synchronized (this) {
            ensureValidLocked();
            return nativeList(mObject, path);
        }
    }

    /**
     * Open a non-asset file as an asset using ACCESS_STREAMING mode.  This
     * provides direct access to all of the files included in an application
     * package (not only its assets).  Applications should not normally use
     * this.
     *
     * @param fileName Name of the asset to retrieve.
     *
     * @see #open(String)
     * @hide
     */
    public @NonNull InputStream openNonAsset(@NonNull String fileName) throws IOException {
        return openNonAsset(0, fileName, ACCESS_STREAMING);
    }

    /**
     * Open a non-asset file as an asset using a specific access mode.  This
     * provides direct access to all of the files included in an application
     * package (not only its assets).  Applications should not normally use
     * this.
     *
     * @param fileName Name of the asset to retrieve.
     * @param accessMode Desired access mode for retrieving the data.
     *
     * @see #ACCESS_UNKNOWN
     * @see #ACCESS_STREAMING
     * @see #ACCESS_RANDOM
     * @see #ACCESS_BUFFER
     * @see #open(String, int)
     * @hide
     */
    public @NonNull InputStream openNonAsset(@NonNull String fileName, int accessMode)
            throws IOException {
        return openNonAsset(0, fileName, accessMode);
    }

    /**
     * Open a non-asset in a specified package.  Not for use by applications.
     *
     * @param cookie Identifier of the package to be opened.
     * @param fileName Name of the asset to retrieve.
     * @hide
     */
    public @NonNull InputStream openNonAsset(int cookie, @NonNull String fileName)
            throws IOException {
        return openNonAsset(cookie, fileName, ACCESS_STREAMING);
    }

    /**
     * Open a non-asset in a specified package.  Not for use by applications.
     *
     * @param cookie Identifier of the package to be opened.
     * @param fileName Name of the asset to retrieve.
     * @param accessMode Desired access mode for retrieving the data.
     * @hide
     */
    public @NonNull InputStream openNonAsset(int cookie, @NonNull String fileName, int accessMode)
            throws IOException {
        Preconditions.checkNotNull(fileName, "fileName");
        synchronized (this) {
            ensureOpenLocked();
            final long asset = nativeOpenNonAsset(mObject, cookie, fileName, accessMode);
            if (asset == 0) {
                throw new FileNotFoundException("Asset absolute file: " + fileName);
            }
            final AssetInputStream assetInputStream = new AssetInputStream(asset);
            incRefsLocked(assetInputStream.hashCode());
            return assetInputStream;
        }
    }

    /**
     * Open a non-asset as an asset by mmapping it and returning an {@link AssetFileDescriptor}.
     * This provides direct access to all of the files included in an application
     * package (not only its assets).  Applications should not normally use this.
     *
     * The asset must not be compressed, or an exception will be thrown.
     *
     * @param fileName Name of the asset to retrieve.
     */
    public @NonNull AssetFileDescriptor openNonAssetFd(@NonNull String fileName)
            throws IOException {
        return openNonAssetFd(0, fileName);
    }

    /**
     * Open a non-asset as an asset by mmapping it and returning an {@link AssetFileDescriptor}.
     * This provides direct access to all of the files included in an application
     * package (not only its assets).  Applications should not normally use this.
     *
     * The asset must not be compressed, or an exception will be thrown.
     *
     * @param cookie Identifier of the package to be opened.
     * @param fileName Name of the asset to retrieve.
     */
    public @NonNull AssetFileDescriptor openNonAssetFd(int cookie, @NonNull String fileName)
            throws IOException {
        Preconditions.checkNotNull(fileName, "fileName");
        synchronized (this) {
            ensureOpenLocked();
            final ParcelFileDescriptor pfd =
                    nativeOpenNonAssetFd(mObject, cookie, fileName, mOffsets);
            if (pfd == null) {
                throw new FileNotFoundException("Asset absolute file: " + fileName);
            }
            return new AssetFileDescriptor(pfd, mOffsets[0], mOffsets[1]);
        }
    }
    
    /**
     * Retrieve a parser for a compiled XML file.
     * 
     * @param fileName The name of the file to retrieve.
     */
    public @NonNull XmlResourceParser openXmlResourceParser(@NonNull String fileName)
            throws IOException {
        return openXmlResourceParser(0, fileName);
    }
    
    /**
     * Retrieve a parser for a compiled XML file.
     * 
     * @param cookie Identifier of the package to be opened.
     * @param fileName The name of the file to retrieve.
     */
    public @NonNull XmlResourceParser openXmlResourceParser(int cookie, @NonNull String fileName)
            throws IOException {
        try (XmlBlock block = openXmlBlockAsset(cookie, fileName)) {
            XmlResourceParser parser = block.newParser();
            // If openXmlBlockAsset doesn't throw, it will always return an XmlBlock object with
            // a valid native pointer, which makes newParser always return non-null. But let's
            // be paranoid.
            if (parser == null) {
                throw new AssertionError("block.newParser() returned a null parser");
            }
            return parser;
        }
    }

    /**
     * Retrieve a non-asset as a compiled XML file.  Not for use by applications.
     * 
     * @param fileName The name of the file to retrieve.
     * @hide
     */
    @NonNull XmlBlock openXmlBlockAsset(@NonNull String fileName) throws IOException {
        return openXmlBlockAsset(0, fileName);
    }

    /**
     * Retrieve a non-asset as a compiled XML file.  Not for use by
     * applications.
     * 
     * @param cookie Identifier of the package to be opened.
     * @param fileName Name of the asset to retrieve.
     * @hide
     */
    @NonNull XmlBlock openXmlBlockAsset(int cookie, @NonNull String fileName) throws IOException {
        Preconditions.checkNotNull(fileName, "fileName");
        synchronized (this) {
            ensureOpenLocked();
            final long xmlBlock = nativeOpenXmlAsset(mObject, cookie, fileName);
            if (xmlBlock == 0) {
                throw new FileNotFoundException("Asset XML file: " + fileName);
            }
            final XmlBlock block = new XmlBlock(this, xmlBlock);
            incRefsLocked(block.hashCode());
            return block;
        }
    }

    void xmlBlockGone(int id) {
        synchronized (this) {
            decRefsLocked(id);
        }
    }

    void applyStyle(long themePtr, @AttrRes int defStyleAttr, @StyleRes int defStyleRes,
            @Nullable XmlBlock.Parser parser, @NonNull int[] inAttrs, long outValuesAddress,
            long outIndicesAddress) {
        Preconditions.checkNotNull(inAttrs, "inAttrs");
        synchronized (this) {
            // Need to synchronize on AssetManager because we will be accessing
            // the native implementation of AssetManager.
            ensureValidLocked();
            nativeApplyStyle(mObject, themePtr, defStyleAttr, defStyleRes,
                    parser != null ? parser.mParseState : 0, inAttrs, outValuesAddress,
                    outIndicesAddress);
        }
    }

    boolean resolveAttrs(long themePtr, @AttrRes int defStyleAttr, @StyleRes int defStyleRes,
            @Nullable int[] inValues, @NonNull int[] inAttrs, @NonNull int[] outValues,
            @NonNull int[] outIndices) {
        Preconditions.checkNotNull(inAttrs, "inAttrs");
        Preconditions.checkNotNull(outValues, "outValues");
        Preconditions.checkNotNull(outIndices, "outIndices");
        synchronized (this) {
            // Need to synchronize on AssetManager because we will be accessing
            // the native implementation of AssetManager.
            ensureValidLocked();
            return nativeResolveAttrs(mObject,
                    themePtr, defStyleAttr, defStyleRes, inValues, inAttrs, outValues, outIndices);
        }
    }

    boolean retrieveAttributes(@NonNull XmlBlock.Parser parser, @NonNull int[] inAttrs,
            @NonNull int[] outValues, @NonNull int[] outIndices) {
        Preconditions.checkNotNull(parser, "parser");
        Preconditions.checkNotNull(inAttrs, "inAttrs");
        Preconditions.checkNotNull(outValues, "outValues");
        Preconditions.checkNotNull(outIndices, "outIndices");
        synchronized (this) {
            // Need to synchronize on AssetManager because we will be accessing
            // the native implementation of AssetManager.
            ensureValidLocked();
            return nativeRetrieveAttributes(
                    mObject, parser.mParseState, inAttrs, outValues, outIndices);
        }
    }

    long createTheme() {
        synchronized (this) {
            ensureValidLocked();
            long themePtr = nativeThemeCreate(mObject);
            incRefsLocked(themePtr);
            return themePtr;
        }
    }

    void releaseTheme(long themePtr) {
        synchronized (this) {
            nativeThemeDestroy(themePtr);
            decRefsLocked(themePtr);
        }
    }

    void applyStyleToTheme(long themePtr, @StyleRes int resId, boolean force) {
        synchronized (this) {
            // Need to synchronize on AssetManager because we will be accessing
            // the native implementation of AssetManager.
            ensureValidLocked();
            nativeThemeApplyStyle(mObject, themePtr, resId, force);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (DEBUG_REFS && mNumRefs != 0) {
            Log.w(TAG, "AssetManager " + this + " finalized with non-zero refs: " + mNumRefs);
            if (mRefStacks != null) {
                for (RuntimeException e : mRefStacks.values()) {
                    Log.w(TAG, "Reference from here", e);
                }
            }
        }

        if (mObject != 0) {
            nativeDestroy(mObject);
        }
    }

    /* No Locking is needed for AssetInputStream because an AssetInputStream is not-thread
    safe and it does not rely on AssetManager once it has been created. It completely owns the
    underlying Asset. */
    public final class AssetInputStream extends InputStream {
        private long mAssetNativePtr;
        private long mLength;
        private long mMarkPos;

        /**
         * @hide
         */
        public final int getAssetInt() {
            throw new UnsupportedOperationException();
        }

        /**
         * @hide
         */
        public final long getNativeAsset() {
            return mAssetNativePtr;
        }

        private AssetInputStream(long assetNativePtr) {
            mAssetNativePtr = assetNativePtr;
            mLength = nativeAssetGetLength(assetNativePtr);
        }

        @Override
        public final int read() throws IOException {
            ensureOpen();
            return nativeAssetReadChar(mAssetNativePtr);
        }

        @Override
        public final int read(@NonNull byte[] b) throws IOException {
            ensureOpen();
            Preconditions.checkNotNull(b, "b");
            return nativeAssetRead(mAssetNativePtr, b, 0, b.length);
        }

        @Override
        public final int read(@NonNull byte[] b, int off, int len) throws IOException {
            ensureOpen();
            Preconditions.checkNotNull(b, "b");
            return nativeAssetRead(mAssetNativePtr, b, off, len);
        }

        @Override
        public final long skip(long n) throws IOException {
            ensureOpen();
            long pos = nativeAssetSeek(mAssetNativePtr, 0, 0);
            if ((pos + n) > mLength) {
                n = mLength - pos;
            }
            if (n > 0) {
                nativeAssetSeek(mAssetNativePtr, n, 0);
            }
            return n;
        }

        @Override
        public final int available() throws IOException {
            ensureOpen();
            final long len = nativeAssetGetRemainingLength(mAssetNativePtr);
            return len > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) len;
        }

        @Override
        public final boolean markSupported() {
            return true;
        }

        @Override
        public final void mark(int readlimit) {
            ensureOpen();
            mMarkPos = nativeAssetSeek(mAssetNativePtr, 0, 0);
        }

        @Override
        public final void reset() throws IOException {
            ensureOpen();
            nativeAssetSeek(mAssetNativePtr, mMarkPos, -1);
        }

        @Override
        public final void close() throws IOException {
            if (mAssetNativePtr != 0) {
                nativeAssetDestroy(mAssetNativePtr);
                mAssetNativePtr = 0;

                synchronized (AssetManager.this) {
                    decRefsLocked(hashCode());
                }
            }
        }

        @Override
        protected void finalize() throws Throwable {
            close();
        }

        private void ensureOpen() {
            if (mAssetNativePtr == 0) {
                throw new IllegalStateException("AssetInputStream is closed");
            }
        }
    }

    /**
     * Determine whether the state in this asset manager is up-to-date with
     * the files on the filesystem.  If false is returned, you need to
     * instantiate a new AssetManager class to see the new data.
     * @hide
     */
    public boolean isUpToDate() {
        for (ApkAssets apkAssets : getApkAssets()) {
            if (!apkAssets.isUpToDate()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get the locales that this asset manager contains data for.
     *
     * <p>On SDK 21 (Android 5.0: Lollipop) and above, Locale strings are valid
     * <a href="https://tools.ietf.org/html/bcp47">BCP-47</a> language tags and can be
     * parsed using {@link java.util.Locale#forLanguageTag(String)}.
     *
     * <p>On SDK 20 (Android 4.4W: Kitkat for watches) and below, locale strings
     * are of the form {@code ll_CC} where {@code ll} is a two letter language code,
     * and {@code CC} is a two letter country code.
     */
    public String[] getLocales() {
        synchronized (this) {
            ensureValidLocked();
            return nativeGetLocales(mObject, false /*excludeSystem*/);
        }
    }

    /**
     * Same as getLocales(), except that locales that are only provided by the system (i.e. those
     * present in framework-res.apk or its overlays) will not be listed.
     *
     * For example, if the "system" assets support English, French, and German, and the additional
     * assets support Cherokee and French, getLocales() would return
     * [Cherokee, English, French, German], while getNonSystemLocales() would return
     * [Cherokee, French].
     * @hide
     */
    public String[] getNonSystemLocales() {
        synchronized (this) {
            ensureValidLocked();
            return nativeGetLocales(mObject, true /*excludeSystem*/);
        }
    }

    /**
     * @hide
     */
    Configuration[] getSizeConfigurations() {
        synchronized (this) {
            ensureValidLocked();
            return nativeGetSizeConfigurations(mObject);
        }
    }

    /**
     * Change the configuration used when retrieving resources.  Not for use by
     * applications.
     * @hide
     */
    public void setConfiguration(int mcc, int mnc, @Nullable String locale, int orientation,
            int touchscreen, int density, int keyboard, int keyboardHidden, int navigation,
            int screenWidth, int screenHeight, int smallestScreenWidthDp, int screenWidthDp,
            int screenHeightDp, int screenLayout, int uiMode, int colorMode, int majorVersion) {
        synchronized (this) {
            ensureValidLocked();
            nativeSetConfiguration(mObject, mcc, mnc, locale, orientation, touchscreen, density,
                    keyboard, keyboardHidden, navigation, screenWidth, screenHeight,
                    smallestScreenWidthDp, screenWidthDp, screenHeightDp, screenLayout, uiMode,
                    colorMode, majorVersion);
        }
    }

    /**
     * @hide
     */
    public SparseArray<String> getAssignedPackageIdentifiers() {
        synchronized (this) {
            ensureValidLocked();
            return nativeGetAssignedPackageIdentifiers(mObject);
        }
    }

    @GuardedBy("this")
    private void incRefsLocked(long id) {
        if (DEBUG_REFS) {
            if (mRefStacks == null) {
                mRefStacks = new HashMap<>();
            }
            RuntimeException ex = new RuntimeException();
            ex.fillInStackTrace();
            mRefStacks.put(id, ex);
        }
        mNumRefs++;
    }

    @GuardedBy("this")
    private void decRefsLocked(long id) {
        if (DEBUG_REFS && mRefStacks != null) {
            mRefStacks.remove(id);
        }
        mNumRefs--;
        if (mNumRefs == 0 && mObject != 0) {
            nativeDestroy(mObject);
            mObject = 0;
            mApkAssets = sEmptyApkAssets;
        }
    }

    // AssetManager setup native methods.
    private static native long nativeCreate();
    private static native void nativeDestroy(long ptr);
    private static native void nativeSetApkAssets(long ptr, @NonNull ApkAssets[] apkAssets,
            boolean invalidateCaches);
    private static native void nativeSetConfiguration(long ptr, int mcc, int mnc,
            @Nullable String locale, int orientation, int touchscreen, int density, int keyboard,
            int keyboardHidden, int navigation, int screenWidth, int screenHeight,
            int smallestScreenWidthDp, int screenWidthDp, int screenHeightDp, int screenLayout,
            int uiMode, int colorMode, int majorVersion);
    private static native @NonNull SparseArray<String> nativeGetAssignedPackageIdentifiers(
            long ptr);

    // File native methods.
    private static native @Nullable String[] nativeList(long ptr, @NonNull String path)
            throws IOException;
    private static native long nativeOpenAsset(long ptr, @NonNull String fileName, int accessMode);
    private static native @Nullable ParcelFileDescriptor nativeOpenAssetFd(long ptr,
            @NonNull String fileName, long[] outOffsets) throws IOException;
    private static native long nativeOpenNonAsset(long ptr, int cookie, @NonNull String fileName,
            int accessMode);
    private static native @Nullable ParcelFileDescriptor nativeOpenNonAssetFd(long ptr, int cookie,
            @NonNull String fileName, @NonNull long[] outOffsets) throws IOException;
    private static native long nativeOpenXmlAsset(long ptr, int cookie, @NonNull String fileName);

    // Primitive resource native methods.
    private static native int nativeGetResourceValue(long ptr, @AnyRes int resId, short density,
            @NonNull TypedValue outValue, boolean resolveReferences);
    private static native int nativeGetResourceBagValue(long ptr, @AnyRes int resId, int bagEntryId,
            @NonNull TypedValue outValue);

    private static native @Nullable @AttrRes int[] nativeGetStyleAttributes(long ptr,
            @StyleRes int resId);
    private static native @Nullable String[] nativeGetResourceStringArray(long ptr,
            @ArrayRes int resId);
    private static native @Nullable int[] nativeGetResourceStringArrayInfo(long ptr,
            @ArrayRes int resId);
    private static native @Nullable int[] nativeGetResourceIntArray(long ptr, @ArrayRes int resId);
    private static native int nativeGetResourceArraySize(long ptr, @ArrayRes int resId);
    private static native int nativeGetResourceArray(long ptr, @ArrayRes int resId,
            @NonNull int[] outValues);

    // Resource name/ID native methods.
    private static native @AnyRes int nativeGetResourceIdentifier(long ptr, @NonNull String name,
            @Nullable String defType, @Nullable String defPackage);
    private static native @Nullable String nativeGetResourceName(long ptr, @AnyRes int resid);
    private static native @Nullable String nativeGetResourcePackageName(long ptr,
            @AnyRes int resid);
    private static native @Nullable String nativeGetResourceTypeName(long ptr, @AnyRes int resid);
    private static native @Nullable String nativeGetResourceEntryName(long ptr, @AnyRes int resid);
    private static native @Nullable String[] nativeGetLocales(long ptr, boolean excludeSystem);
    private static native @Nullable Configuration[] nativeGetSizeConfigurations(long ptr);

    // Style attribute retrieval native methods.
    private static native void nativeApplyStyle(long ptr, long themePtr, @AttrRes int defStyleAttr,
            @StyleRes int defStyleRes, long xmlParserPtr, @NonNull int[] inAttrs,
            long outValuesAddress, long outIndicesAddress);
    private static native boolean nativeResolveAttrs(long ptr, long themePtr,
            @AttrRes int defStyleAttr, @StyleRes int defStyleRes, @Nullable int[] inValues,
            @NonNull int[] inAttrs, @NonNull int[] outValues, @NonNull int[] outIndices);
    private static native boolean nativeRetrieveAttributes(long ptr, long xmlParserPtr,
            @NonNull int[] inAttrs, @NonNull int[] outValues, @NonNull int[] outIndices);

    // Theme related native methods
    private static native long nativeThemeCreate(long ptr);
    private static native void nativeThemeDestroy(long themePtr);
    private static native void nativeThemeApplyStyle(long ptr, long themePtr, @StyleRes int resId,
            boolean force);
    static native void nativeThemeCopy(long destThemePtr, long sourceThemePtr);
    static native void nativeThemeClear(long themePtr);
    private static native int nativeThemeGetAttributeValue(long ptr, long themePtr,
            @AttrRes int resId, @NonNull TypedValue outValue, boolean resolve);
    private static native void nativeThemeDump(long ptr, long themePtr, int priority, String tag,
            String prefix);
    static native @NativeConfig int nativeThemeGetChangingConfigurations(long themePtr);

    // AssetInputStream related native methods.
    private static native void nativeAssetDestroy(long assetPtr);
    private static native int nativeAssetReadChar(long assetPtr);
    private static native int nativeAssetRead(long assetPtr, byte[] b, int off, int len);
    private static native long nativeAssetSeek(long assetPtr, long offset, int whence);
    private static native long nativeAssetGetLength(long assetPtr);
    private static native long nativeAssetGetRemainingLength(long assetPtr);

    private static native void nativeVerifySystemIdmaps();

    // Global debug native methods.
    /**
     * @hide
     */
    public static native int getGlobalAssetCount();

    /**
     * @hide
     */
    public static native String getAssetAllocations();

    /**
     * @hide
     */
    public static native int getGlobalAssetManagerCount();
}
