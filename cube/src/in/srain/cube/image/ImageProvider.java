package in.srain.cube.image;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build.VERSION_CODES;
import android.util.Log;
import in.srain.cube.image.drawable.RecyclingBitmapDrawable;
import in.srain.cube.image.iface.ImageFileProvider;
import in.srain.cube.image.iface.ImageMemoryCache;
import in.srain.cube.image.iface.ImageResizer;
import in.srain.cube.image.impl.DefaultMemoryCache;
import in.srain.cube.image.impl.LruImageFileProvider;
import in.srain.cube.util.Debug;
import in.srain.cube.util.Version;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * This class handles disk and memory caching of bitmaps.
 * <p/>
 * Most of the code is taken from the Android best practice of displaying Bitmaps <a href="http://developer.android.com/training/displaying-bitmaps/index.html">Displaying Bitmaps Efficiently</a>.
 *
 * @author http://www.liaohuqiu.net
 */
public class ImageProvider {

    protected static final boolean DEBUG = Debug.DEBUG_IMAGE;

    protected static final String TAG = "image_provider";

    private static final String MSG_FETCH_BEGIN = "%s fetchBitmapData";
    private static final String MSG_FETCH_BEGIN_IDENTITY_KEY = "%s identityKey: %s";
    private static final String MSG_FETCH_BEGIN_FILE_CACHE_KEY = "%s fileCacheKey: %s";
    private static final String MSG_FETCH_BEGIN_IDENTITY_URL = "%s identityUrl: %s";
    private static final String MSG_FETCH_BEGIN_ORIGIN_URL = "%s originUrl: %s";

    private static final String MSG_FETCH_TRY_REUSE = "%s Disk Cache not hit. Try to reuse";
    private static final String MSG_FETCH_HIT_DISK_CACHE = "%s Disk Cache hit";
    private static final String MSG_FETCH_REUSE_SUCCESS = "%s reuse size: %s";
    private static final String MSG_FETCH_REUSE_FAIL = "%s reuse fail: %s, %s";
    private static final String MSG_FETCH_DOWNLOAD = "%s downloading: %s";
    private static final String MSG_DECODE = "%s decode: %sx%s inSampleSize:%s";

    private ImageMemoryCache mMemoryCache;
    private ImageFileProvider mImageFileProvider;

    private static ImageProvider sDefault;

    public static ImageProvider getDefault(Context context) {
        if (null == sDefault) {
            sDefault = new ImageProvider(context, DefaultMemoryCache.getDefault(), LruImageFileProvider.getDefault(context));
        }
        return sDefault;
    }

    public ImageProvider(Context context, ImageMemoryCache memoryCache, ImageFileProvider fileProvider) {
        mMemoryCache = memoryCache;
        mImageFileProvider = fileProvider;
    }

    /**
     * Create a BitmapDrawable which can be managed in ImageProvider
     *
     * @param resources
     * @param bitmap
     * @return
     */
    public BitmapDrawable createBitmapDrawable(Resources resources, Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        BitmapDrawable drawable = null;
        if (bitmap != null) {
            if (Version.hasHoneycomb()) {
                // Running on Honeycomb or newer, so wrap in a standard BitmapDrawable
                drawable = new BitmapDrawable(resources, bitmap);
            } else {
                // Running on Gingerbread or older, so wrap in a RecyclingBitmapDrawable
                // which will recycle automatically
                drawable = new RecyclingBitmapDrawable(resources, bitmap);
            }
        }
        return drawable;
    }

    /**
     * Get from memory cache.
     */
    public BitmapDrawable getBitmapFromMemCache(ImageTask imageTask) {
        BitmapDrawable memValue = null;

        if (mMemoryCache != null) {
            memValue = mMemoryCache.get(imageTask.getIdentityKey());
        }

        return memValue;
    }

    public void addBitmapToMemCache(String key, BitmapDrawable drawable) {

        // If the API level is lower than 11, do not use memory cache
        if (key == null || drawable == null || !Version.hasHoneycomb()) {
            return;
        }

        // Add to memory cache
        if (mMemoryCache != null) {
            mMemoryCache.set(key, drawable);
        }
    }

    /**
     * Get Bitmap. If not exist in file cache, will try to re-use the file cache of the other sizes.
     * <p/>
     * If no file cache can be used, download then save to file.
     */
    public Bitmap fetchBitmapData(ImageTask imageTask, ImageResizer imageResizer) {
        Bitmap bitmap = null;
        if (mImageFileProvider != null) {
            FileInputStream inputStream = null;

            String fileCacheKey = imageTask.getFileCacheKey();
            ImageReuseInfo reuseInfo = imageTask.getImageReuseInfo();

            if (DEBUG) {
                Log.d(TAG, String.format(MSG_FETCH_BEGIN, imageTask));
                Log.d(TAG, String.format(MSG_FETCH_BEGIN_IDENTITY_KEY, imageTask, imageTask.getIdentityKey()));
                Log.d(TAG, String.format(MSG_FETCH_BEGIN_FILE_CACHE_KEY, imageTask, fileCacheKey));
                Log.d(TAG, String.format(MSG_FETCH_BEGIN_ORIGIN_URL, imageTask, imageTask.getOriginUrl()));
                Log.d(TAG, String.format(MSG_FETCH_BEGIN_IDENTITY_URL, imageTask, imageTask.getIdentityUrl()));
            }

            // read from file cache
            inputStream = mImageFileProvider.getInputStream(fileCacheKey);

            // try to reuse
            if (inputStream == null) {
                if (reuseInfo != null && reuseInfo.getReuseSizeList() != null && reuseInfo.getReuseSizeList().length > 0) {
                    if (DEBUG) {
                        Log.d(TAG, String.format(MSG_FETCH_TRY_REUSE, imageTask));
                    }

                    final String[] sizeKeyList = reuseInfo.getReuseSizeList();
                    for (int i = 0; i < sizeKeyList.length; i++) {
                        String size = sizeKeyList[i];
                        final String key = imageTask.generateFileCacheKeyForReuse(size);
                        inputStream = mImageFileProvider.getInputStream(key);

                        if (inputStream != null) {
                            if (DEBUG) {
                                Log.d(TAG, String.format(MSG_FETCH_REUSE_SUCCESS, imageTask, size));
                            }
                            break;
                        } else {
                            if (DEBUG) {
                                Log.d(TAG, String.format(MSG_FETCH_REUSE_FAIL, imageTask, size, key));
                            }
                        }
                    }
                }
            } else {
                if (DEBUG) {
                    Log.d(TAG, String.format(MSG_FETCH_HIT_DISK_CACHE, imageTask));
                }
            }

            if (imageTask.getStatistics() != null) {
                imageTask.getStatistics().afterFileCache(inputStream != null);
            }

            // We've got nothing from file cache
            if (inputStream == null) {
                String url = imageResizer.getRemoteUrl(imageTask);
                if (DEBUG) {
                    Log.d(TAG, String.format(MSG_FETCH_DOWNLOAD, imageTask, url));
                }
                inputStream = mImageFileProvider.downloadAndGetInputStream(fileCacheKey, url);
                if (imageTask.getStatistics() != null) {
                    imageTask.getStatistics().afterDownload();
                }
                if (inputStream == null) {
                    Log.e(TAG, imageTask + " download fail: " + fileCacheKey);
                }
            }
            if (inputStream != null) {
                try {
                    bitmap = decodeSampledBitmapFromDescriptor(inputStream.getFD(), imageTask, imageResizer);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                Log.e(TAG, imageTask + " fetch bitmap fail. file cache key: " + fileCacheKey);
            }
        }
        return bitmap;
    }

    private Bitmap decodeSampledBitmapFromDescriptor(FileDescriptor fileDescriptor, ImageTask imageTask, ImageResizer imageResizer) {

        // First decode with inJustDecodeBounds=true to check dimensions
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options);

        imageTask.setBitmapOriginSize(options.outWidth, options.outHeight);

        // Calculate inSampleSize
        options.inSampleSize = imageResizer.getInSampleSize(imageTask);

        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;

        if (DEBUG) {
            Log.d(TAG, String.format(MSG_DECODE, imageTask, imageTask.getBitmapOriginSize().x, imageTask.getBitmapOriginSize().y, options.inSampleSize));
        }

        Bitmap bitmap = BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options);

        return bitmap;
    }

    private Bitmap decodeSampledBitmapFromInputStream(InputStream stream, ImageTask imageTask, ImageResizer imageResizer) {
        // First decode with inJustDecodeBounds=true to check dimensions
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;

        // try to decode height and width from InputStream
        BitmapFactory.decodeStream(stream, null, options);

        imageTask.setBitmapOriginSize(options.outWidth, options.outHeight);

        // Calculate inSampleSize
        options.inSampleSize = imageResizer.getInSampleSize(imageTask);

        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;

        if (DEBUG) {
            Log.d(TAG, String.format(MSG_DECODE, imageTask, imageTask.getBitmapOriginSize().x, imageTask.getBitmapOriginSize().y, options.inSampleSize));
        }

        Bitmap bitmap = BitmapFactory.decodeStream(stream, null, options);

        return bitmap;
    }

    public void flushFileCache() {
        if (null != mImageFileProvider) {
            mImageFileProvider.flushDiskCacheAsync();
        }
    }

    /**
     * clear the memory cache
     */
    public void clearMemoryCache() {
        if (mMemoryCache != null) {
            mMemoryCache.clear();
        }
    }

    /**
     * clear the disk cache
     */
    public void clearDiskCache() {
        if (null != mImageFileProvider) {
            mImageFileProvider.clearCache();
        }
    }

    public long getMemoryCacheMaxSpace() {
        return mMemoryCache.getMaxSize();
    }

    public long getMemoryCacheUsedSpace() {
        return mMemoryCache.getUsedSpace();
    }

    /**
     * return the file cache path
     *
     * @return
     */
    public String getFileCachePath() {
        if (null != mImageFileProvider) {
            return mImageFileProvider.getCachePath();
        }
        return null;
    }

    /**
     * get the used space
     *
     * @return
     */
    public long getFileCacheUsedSpace() {
        return null != mImageFileProvider ? mImageFileProvider.getUsedSpace() : 0;
    }

    public long getFileCacheMaxSpace() {
        if (null != mImageFileProvider) {
            return mImageFileProvider.getMaxSize();
        }
        return 0;
    }

    /**
     * Return the byte usage per pixel of a bitmap based on its configuration.
     *
     * @param config The bitmap configuration.
     * @return The byte usage per pixel.
     */
    private static int getBytesPerPixel(Config config) {
        if (config == Config.ARGB_8888) {
            return 4;
        } else if (config == Config.RGB_565) {
            return 2;
        } else if (config == Config.ARGB_4444) {
            return 2;
        } else if (config == Config.ALPHA_8) {
            return 1;
        }
        return 1;
    }

    /**
     * Get the size in bytes of a bitmap in a BitmapDrawable. Note that from Android 4.4 (KitKat) onward this returns the allocated memory size of the bitmap which can be larger than the actual bitmap data byte count (in the case it was re-used).
     *
     * @param value
     * @return size in bytes
     */
    public static int getBitmapSize(BitmapDrawable value) {
        if (null == value) {
            return 0;
        }
        Bitmap bitmap = value.getBitmap();

        // From KitKat onward use getAllocationByteCount() as allocated bytes can potentially be
        // larger than bitmap byte count.
        if (Version.hasKitKat()) {
            return bitmap.getByteCount();
        }

        if (Version.hasHoneycombMR1()) {
            return bitmap.getByteCount();
        }

        // Pre HC-MR1
        return bitmap.getRowBytes() * bitmap.getHeight();
    }
}
