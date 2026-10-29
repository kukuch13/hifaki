package id.hifaki.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.UUID;

/** Foto pemeriksaan privat: salinan JPEG tanpa metadata EXIF, tidak ikut sinkronisasi. */
public final class PhotoStore {
    private final Context context;
    private final File captureDir;
    private final File draftDir;
    private final File savedDir;

    public PhotoStore(Context context) {
        this.context = context.getApplicationContext();
        captureDir = new File(context.getCacheDir(), "hifaki_camera");
        draftDir = new File(context.getCacheDir(), "hifaki_drafts");
        savedDir = new File(context.getFilesDir(), "hifaki_photos");
        captureDir.mkdirs();
        draftDir.mkdirs();
        savedDir.mkdirs();
        cleanupOld(captureDir);
        cleanupOld(draftDir);
    }

    private static void cleanupOld(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long before = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for (File file : files) if (file.isFile() && file.lastModified() < before) file.delete();
    }

    public File newCaptureFile() throws IOException {
        return File.createTempFile("capture_", ".jpg", captureDir);
    }

    public Uri captureUri(File file) {
        return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
    }

    /** Salin dan kecilkan foto, putar orientasi umum, lalu buang EXIF termasuk tag lokasi. */
    public File importImage(Uri uri) throws IOException {
        int rotation = 0;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                int orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
            }
        } catch (IOException ignored) { /* Gambar tanpa EXIF tetap dapat dibuka. */ }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        if (bounds.outWidth < 1 || bounds.outHeight < 1) throw new IOException("Format foto tidak didukung");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 2000 ||
                bounds.outHeight / options.inSampleSize > 2000) options.inSampleSize *= 2;
        Bitmap image;
        try (InputStream in = open(uri)) { image = BitmapFactory.decodeStream(in, null, options); }
        if (image == null) throw new IOException("Foto tidak dapat dibaca");

        File result = null;
        try {
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.setRotate(rotation);
                Bitmap upright = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), matrix, true);
                if (upright != image) image.recycle();
                image = upright;
            }
            int max = Math.max(image.getWidth(), image.getHeight());
            if (max > 1600) {
                Bitmap scaled = Bitmap.createScaledBitmap(image,
                        Math.max(1, Math.round(image.getWidth() * 1600f / max)),
                        Math.max(1, Math.round(image.getHeight() * 1600f / max)), true);
                if (scaled != image) image.recycle();
                image = scaled;
            }
            result = File.createTempFile("photo_", ".jpg", draftDir);
            try (FileOutputStream out = new FileOutputStream(result)) {
                if (!image.compress(Bitmap.CompressFormat.JPEG, 82, out))
                    throw new IOException("Foto gagal disimpan");
            }
            return result;
        } catch (IOException | RuntimeException e) {
            if (result != null) result.delete();
            throw e;
        } finally { image.recycle(); }
    }

    private InputStream open(Uri uri) throws IOException {
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Foto tidak tersedia");
        return in;
    }

    public Bitmap preview(File photo) {
        if (photo == null || !photo.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(photo.getAbsolutePath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 512 ||
                bounds.outHeight / options.inSampleSize > 512) options.inSampleSize *= 2;
        return BitmapFactory.decodeFile(photo.getAbsolutePath(), options);
    }

    public Bitmap previewSaved(String fileName) {
        return validName(fileName) ? preview(new File(savedDir, fileName)) : null;
    }

    public Bitmap fullSaved(String fileName) {
        if (!validName(fileName)) return null;
        File file = new File(savedDir, fileName);
        if (!file.isFile()) return null;
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    public String getBase64(String fileName) {
        if (!validName(fileName)) return null;
        File file = new File(savedDir, fileName);
        if (!file.isFile()) return null;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    public String saveFromBase64(String base64Str) {
        if (base64Str == null || base64Str.trim().isEmpty()) return null;
        try {
            byte[] bytes = android.util.Base64.decode(base64Str.trim(), android.util.Base64.DEFAULT);
            if (bytes == null || bytes.length == 0) return null;
            String fileName = UUID.randomUUID() + ".jpg";
            File saved = new File(savedDir, fileName);
            try (FileOutputStream out = new FileOutputStream(saved)) {
                out.write(bytes);
            }
            return fileName;
        } catch (Exception e) {
            return null;
        }
    }

    public String persist(File draft) throws IOException {
        if (draft == null) return null;
        if (!draft.isFile() || !draft.getCanonicalFile().getParentFile().equals(draftDir.getCanonicalFile()))
            throw new IOException("Foto sementara tidak tersedia");
        String fileName = UUID.randomUUID() + ".jpg";
        File saved = new File(savedDir, fileName);
        try { Files.copy(draft.toPath(), saved.toPath()); }
        catch (IOException e) { saved.delete(); throw e; }
        return fileName;
    }

    public void deleteDraft(File file) {
        if (file != null) file.delete();
    }

    public void deleteSaved(String fileName) {
        if (validName(fileName)) new File(savedDir, fileName).delete();
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[0-9a-f-]{36}\\.jpg");
    }
}
