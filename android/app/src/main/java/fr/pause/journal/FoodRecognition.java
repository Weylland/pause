package fr.pause.journal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.oned.UPCEReader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class FoodRecognition {
    record Product(String name, Double kcalPer100, String source, String note) {}
    static final Pattern CODE = Pattern.compile("[0-9]{8}|[0-9]{12,14}");
    private static final Pattern KCAL = Pattern.compile("(?<![0-9.,])([0-9]{1,4}(?:[.,][0-9]{1,2})?)\\s*kcal\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BASE = Pattern.compile("(?<![0-9])100\\s*(g|ml)\\b", Pattern.CASE_INSENSITIVE);

    static String barcode(Context context, Uri uri) throws IOException, NotFoundException {
        Result result = decode(context, uri, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.ITF);
        String code = result.getText();
        if (result.getBarcodeFormat() == BarcodeFormat.UPC_E) code = UPCEReader.convertUPCEtoUPCA(code);
        if (!CODE.matcher(code).matches()) throw NotFoundException.getNotFoundInstance();
        return code;
    }

    static String qr(Context context, Uri uri) throws IOException, NotFoundException {
        return decode(context, uri, BarcodeFormat.QR_CODE).getText();
    }

    private static Result decode(Context context, Uri uri, BarcodeFormat... formats)
            throws IOException, NotFoundException {
        Bitmap bitmap = load(context, uri, 2048);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, true);
        hints.put(DecodeHintType.ALSO_INVERTED, true);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(formats));
        MultiFormatReader reader = new MultiFormatReader();
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                int width = bitmap.getWidth(), height = bitmap.getHeight();
                int[] pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                try {
                    return reader.decode(new BinaryBitmap(new HybridBinarizer(
                            new RGBLuminanceSource(width, height, pixels))), hints);
                } catch (NotFoundException ignored) {
                    // Le code peut être orienté verticalement dans la photo.
                }
                if (attempt == 0) bitmap = rotate(bitmap, 90);
            }
            throw NotFoundException.getNotFoundInstance();
        } finally {
            bitmap.recycle();
        }
    }

    private static Bitmap load(Context context, Uri uri, int maxSize) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, options);
        }
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxSize * 2) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        Bitmap image;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            image = BitmapFactory.decodeStream(input, null, options);
        }
        if (image == null) throw new IOException("Image illisible.");
        if (Math.max(image.getWidth(), image.getHeight()) > maxSize) {
            float scale = (float) maxSize / Math.max(image.getWidth(), image.getHeight());
            Bitmap smaller = Bitmap.createScaledBitmap(image, Math.max(1, Math.round(image.getWidth() * scale)),
                    Math.max(1, Math.round(image.getHeight() * scale)), true);
            image.recycle();
            image = smaller;
        }
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            int orientation = new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) return rotate(image, 90);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_180) return rotate(image, 180);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_270) return rotate(image, 270);
        } catch (IOException ignored) {
            // Les images sans métadonnées restent utilisables.
        }
        return image;
    }

    private static Bitmap rotate(Bitmap image, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        Bitmap rotated = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), matrix, true);
        if (rotated != image) image.recycle();
        return rotated;
    }

    static Product lookup(String barcode) throws Exception {
        if (!CODE.matcher(barcode).matches()) throw new IllegalArgumentException("Code-barres invalide.");
        URL url = new URL("https://world.openfoodfacts.org/api/v2/product/" + barcode
                + ".json?fields=product_name_fr,product_name,nutriments");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setRequestProperty("User-Agent", "Pause-Android/1.0 (Android personal journal)");
        connection.setRequestProperty("Accept", "application/json");
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(6000);
        try {
            int status = connection.getResponseCode();
            if (status == 404) return null;
            if (status != 200) throw new IOException("Réponse HTTP " + status);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[4096];
                for (int count; (count = input.read(buffer)) != -1;) {
                    bytes.write(buffer, 0, count);
                    if (bytes.size() > 262144) throw new IOException("Réponse trop grande.");
                }
            }
            JSONObject product = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).optJSONObject("product");
            if (product == null) return null;
            String name = product.isNull("product_name_fr") ? "" : product.optString("product_name_fr", "").trim();
            if (name.isEmpty() && !product.isNull("product_name")) name = product.optString("product_name", "").trim();
            JSONObject nutriments = product.optJSONObject("nutriments");
            double kcal = nutriments == null ? Double.NaN : nutriments.optDouble("energy-kcal_100g");
            Double value = Double.isFinite(kcal) && kcal >= 0 && kcal <= 1000 ? kcal : null;
            return new Product(name, value, "Open Food Facts",
                    value == null ? "Valeur énergétique manquante. Tu peux la saisir depuis l’étiquette."
                            : "Vérifie la valeur sur l’emballage et indique la quantité consommée.");
        } finally {
            connection.disconnect();
        }
    }

    static Product label(String text) {
        Matcher values = KCAL.matcher(text);
        Double kcal = null;
        if (values.find()) {
            double first = Double.parseDouble(values.group(1).replace(',', '.'));
            if (!values.find() && BASE.matcher(text).find() && first <= 1000) kcal = first;
        }
        String note = kcal == null ? "Vérifie la base (100 g ou 100 ml) et saisis la valeur de l’étiquette."
                : "Vérifie la valeur lue et choisis l’unité de ta portion.";
        StringBuilder energyLines = new StringBuilder();
        for (String line : text.split("\\R")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("kcal") || lower.contains("énergie") || BASE.matcher(line).find()) {
                if (energyLines.length() > 0) energyLines.append(" • ");
                energyLines.append(line.trim());
                if (energyLines.length() > 200) break;
            }
        }
        if (energyLines.length() > 0) note += "\nLu : " + energyLines;
        return new Product("", kcal, "Étiquette", note);
    }
}
