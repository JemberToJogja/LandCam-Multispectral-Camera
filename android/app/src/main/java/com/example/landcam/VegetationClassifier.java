package com.example.landcam;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LANDCAM semantic vegetation segmentation adapter.
 *
 * This class deliberately does NOT use an RGB/green threshold as a vegetation
 * classifier. A semantic mask is produced only by a real TensorFlow Lite
 * segmentation model. If the runtime, model asset, label map, or model tensor
 * contract is missing/incompatible, the classifier stays unavailable and does
 * not fabricate a mask.
 *
 * Required app assets:
 *   assets/vegetation_segmentation.tflite
 *   assets/vegetation_labels.txt
 *
 * Model contract:
 *   - TFLite Interpreter runtime must be present in the app.
 *   - Input tensor 0: FLOAT32 [1, height, width, 3], RGB channel order.
 *   - Input values: RGB channels normalized to [0, 1] (pixel / 255.0).
 *   - Output tensor 0: FLOAT32 [1, outputHeight, outputWidth, classes].
 *   - For classes >= 2, output values must be per-pixel class probabilities
 *     (softmax-style); labels file has one class label per channel, in order.
 *   - For a single output channel, the value is the vegetation probability
 *     (sigmoid-style), and the sole label must identify vegetation.
 *
 * A loaded model is NOT automatically considered scientifically validated.
 * Call setValidationApproval(true, evidenceDescription) only after testing the
 * exact model on a representative labelled validation set including live plants,
 * hands/skin, shoes, artificial foliage/plastic, soil, glass/reflections, and
 * varying light/shadows. Until then, prediction masks are review-only and
 * getVegetationMask() returns null.
 *
 * TFLite is accessed reflectively so this source does not impose a compile-time
 * TensorFlow import on projects that do not include the runtime. The runtime is
 * still required at runtime for inference.
 */
public final class VegetationClassifier implements Closeable {

    private static final String TAG = "LandCamVegetation";

    public static final String DEFAULT_MODEL_ASSET = "vegetation_segmentation.tflite";
    public static final String DEFAULT_LABELS_ASSET = "vegetation_labels.txt";

    public static final String STATUS_NOT_INITIALIZED = "NOT_INITIALIZED";
    public static final String STATUS_READY_UNVALIDATED = "READY_UNVALIDATED";
    public static final String STATUS_READY_VALIDATED = "READY_VALIDATED";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";
    public static final String STATUS_CLOSED = "CLOSED";

    private static final String TFLITE_INTERPRETER_CLASS = "org.tensorflow.lite.Interpreter";
    private static final int MAX_TENSOR_PIXELS = 1_048_576;
    private static final float DEFAULT_CONFIDENCE_THRESHOLD = 0.70f;
    private static final float PROBABILITY_EPSILON = 0.001f;

    private final Context appContext;
    private final String modelAssetName;
    private final String labelsAssetName;

    private volatile Object interpreter;
    private volatile File cachedModelFile;
    private volatile String status = STATUS_NOT_INITIALIZED;
    private volatile String statusMessage = "Classifier has not been initialized.";
    private volatile String lastInferenceError = "";

    private volatile boolean modelLoaded;
    private volatile boolean validationApproved;
    private volatile String validationEvidence = "";

    private volatile int inputWidth;
    private volatile int inputHeight;
    private volatile int outputWidth;
    private volatile int outputHeight;
    private volatile int outputClassCount;
    private volatile int vegetationClassIndex = -1;
    private volatile boolean singleChannelVegetationProbability;
    private volatile float confidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD;
    private volatile List<String> labels = Collections.emptyList();

    public VegetationClassifier(Context context) {
        this(context, DEFAULT_MODEL_ASSET, DEFAULT_LABELS_ASSET);
    }

    public VegetationClassifier(
            Context context,
            String modelAssetName,
            String labelsAssetName
    ) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.appContext = context.getApplicationContext();
        this.modelAssetName = requireAssetName(modelAssetName, "modelAssetName");
        this.labelsAssetName = requireAssetName(labelsAssetName, "labelsAssetName");
    }

    /**
     * Loads the model and validates its tensor/label contract. Run this on a
     * background thread; copying the model and initializing the interpreter may
     * take noticeable time on first use.
     */
    public synchronized boolean initialize() {
        closeInterpreterQuietly();
        modelLoaded = false;
        validationApproved = false;
        validationEvidence = "";
        labels = Collections.emptyList();
        vegetationClassIndex = -1;
        singleChannelVegetationProbability = false;
        lastInferenceError = "";
        inputWidth = 0;
        inputHeight = 0;
        outputWidth = 0;
        outputHeight = 0;
        outputClassCount = 0;
        cachedModelFile = null;

        Object candidateInterpreter = null;
        try {
            List<String> loadedLabels = readLabels(appContext.getAssets(), labelsAssetName);
            if (loadedLabels.isEmpty()) {
                return failUnavailable("LABELS_FILE_EMPTY");
            }

            File modelFile = copyAssetToCache(
                    appContext.getAssets(), modelAssetName);
            if (modelFile == null || !modelFile.isFile() || modelFile.length() <= 0L) {
                return failUnavailable("MODEL_ASSET_MISSING_OR_EMPTY");
            }

            Class<?> interpreterClass;
            try {
                interpreterClass = Class.forName(TFLITE_INTERPRETER_CLASS);
            } catch (ClassNotFoundException e) {
                return failUnavailable(
                        "TFLITE_RUNTIME_MISSING: add the TensorFlow Lite runtime dependency.");
            }

            Constructor<?> constructor = interpreterClass.getConstructor(File.class);
            candidateInterpreter = constructor.newInstance(modelFile);

            int[] inputShape = tensorShape(candidateInterpreter, "getInputTensor", 0);
            int[] outputShape = tensorShape(candidateInterpreter, "getOutputTensor", 0);
            String inputType = tensorDataType(candidateInterpreter, "getInputTensor", 0);
            String outputType = tensorDataType(candidateInterpreter, "getOutputTensor", 0);

            validateInputShape(inputShape, inputType);
            validateOutputShape(outputShape, outputType);

            int candidateInputHeight = inputShape[1];
            int candidateInputWidth = inputShape[2];
            int candidateOutputHeight = outputShape[1];
            int candidateOutputWidth = outputShape[2];
            int candidateClassCount = outputShape[3];

            if (loadedLabels.size() != candidateClassCount) {
                closeObjectQuietly(candidateInterpreter);
                candidateInterpreter = null;
                return failUnavailable(
                        "LABEL_COUNT_MISMATCH: model output channels=" + candidateClassCount
                                + ", labels=" + loadedLabels.size());
            }

            int candidateVegetationIndex = findVegetationLabel(loadedLabels);
            boolean candidateSingleChannel = candidateClassCount == 1;
            if (candidateVegetationIndex < 0) {
                closeObjectQuietly(candidateInterpreter);
                candidateInterpreter = null;
                return failUnavailable(
                        "VEGETATION_LABEL_MISSING: labels must explicitly name the vegetation class.");
            }
            if (candidateSingleChannel && candidateVegetationIndex != 0) {
                closeObjectQuietly(candidateInterpreter);
                candidateInterpreter = null;
                return failUnavailable("INVALID_SINGLE_CHANNEL_LABEL_MAP");
            }

            this.interpreter = candidateInterpreter;
            candidateInterpreter = null;
            this.cachedModelFile = modelFile;
            this.inputWidth = candidateInputWidth;
            this.inputHeight = candidateInputHeight;
            this.outputWidth = candidateOutputWidth;
            this.outputHeight = candidateOutputHeight;
            this.outputClassCount = candidateClassCount;
            this.vegetationClassIndex = candidateVegetationIndex;
            this.singleChannelVegetationProbability = candidateSingleChannel;
            this.labels = Collections.unmodifiableList(new ArrayList<>(loadedLabels));
            this.modelLoaded = true;
            this.status = STATUS_READY_UNVALIDATED;
            this.statusMessage = "Model loaded. Semantic output remains review-only until validation is approved.";

            Log.i(TAG, "Segmentation model loaded: input=" + inputWidth + "x" + inputHeight
                    + ", output=" + outputWidth + "x" + outputHeight
                    + ", classes=" + outputClassCount
                    + ", vegetationIndex=" + vegetationClassIndex
                    + ", validated=false");
            return true;
        } catch (OutOfMemoryError e) {
            closeObjectQuietly(candidateInterpreter);
            closeInterpreterQuietly();
            return failUnavailable("OUT_OF_MEMORY_WHILE_LOADING_MODEL");
        } catch (Exception e) {
            closeObjectQuietly(candidateInterpreter);
            closeInterpreterQuietly();
            String message = rootMessage(e);
            Log.e(TAG, "Could not initialize semantic vegetation model: " + message, e);
            return failUnavailable("MODEL_INITIALIZATION_FAILED: " + message);
        }
    }

    /**
     * Sets a confidence threshold in [0.50, 0.99]. Pixels below the threshold
     * are represented as unknown/transparent in the review mask, not as plants.
     */
    public synchronized boolean setConfidenceThreshold(float threshold) {
        if (Float.isNaN(threshold) || Float.isInfinite(threshold)
                || threshold < 0.50f || threshold > 0.99f) {
            return false;
        }
        confidenceThreshold = threshold;
        return true;
    }

    public float getConfidenceThreshold() {
        return confidenceThreshold;
    }

    /**
     * Marks a model as approved for semantic-mask use after external evaluation.
     * This flag is intentionally false after each initialize() and is never
     * inferred from merely loading a model.
     */
    public synchronized boolean setValidationApproval(
            boolean approved,
            String evidenceDescription
    ) {
        if (!approved) {
            validationApproved = false;
            validationEvidence = "";
            if (modelLoaded) {
                status = STATUS_READY_UNVALIDATED;
                statusMessage = "Model loaded but not approved for semantic-mask decisions.";
            }
            return true;
        }

        if (!modelLoaded || evidenceDescription == null
                || evidenceDescription.trim().length() < 8) {
            return false;
        }

        validationApproved = true;
        validationEvidence = evidenceDescription.trim();
        status = STATUS_READY_VALIDATED;
        statusMessage = "Model loaded and explicitly approved by the integrator; validation evidence was recorded.";
        Log.w(TAG, "Semantic classifier marked validated by integration configuration. Verify the referenced test evidence exists.");
        return true;
    }

    /** True when the model runtime and model assets loaded successfully. */
    public boolean isModelLoaded() {
        return modelLoaded && interpreter != null;
    }

    /** True only after explicit validation approval, not merely after loading. */
    public boolean isModelValidated() {
        return isModelLoaded() && validationApproved;
    }

    /** Use this guard before enabling semantic filtering in a production workflow. */
    public boolean isSemanticClassificationAvailable() {
        return isModelValidated();
    }

    public String getStatus() {
        return status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    /** Returns a Flutter/MethodChannel-safe status payload. */
    public synchronized Map<String, Object> getStatusMap() {
        HashMap<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("message", statusMessage);
        result.put("modelLoaded", isModelLoaded());
        result.put("modelValidated", isModelValidated());
        result.put("semanticClassificationAvailable", isSemanticClassificationAvailable());
        result.put("modelAsset", modelAssetName);
        result.put("labelsAsset", labelsAssetName);
        result.put("inputWidth", inputWidth);
        result.put("inputHeight", inputHeight);
        result.put("outputWidth", outputWidth);
        result.put("outputHeight", outputHeight);
        result.put("outputClassCount", outputClassCount);
        result.put("vegetationClassIndex", vegetationClassIndex);
        result.put("confidenceThreshold", confidenceThreshold);
        result.put("validationEvidence", validationEvidence);
        result.put("lastInferenceError", lastInferenceError);
        result.put("inputContract", "FLOAT32_RGB_0_TO_1");
        result.put("outputContract", "FLOAT32_SEGMENTATION_PROBABILITIES");
        result.put("metric", "SEMANTIC_VEGETATION_MASK_ONLY_NOT_VEGETATION_HEALTH");
        return result;
    }

    /**
     * Runs semantic segmentation. The inference mask is for review even when
     * unvalidated; call result.getVegetationMask() to obtain a mask only when
     * the model has explicit validation approval. Otherwise it returns null.
     *
     * The input Bitmap is never modified or recycled by this class. Caller owns
     * and must recycle a returned mask preview when finished with it.
     */
    public synchronized ClassificationResult classify(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return ClassificationResult.failure("INVALID_SOURCE_BITMAP", isModelValidated());
        }
        if (!isModelLoaded()) {
            return ClassificationResult.failure(
                    statusMessage == null ? STATUS_UNAVAILABLE : statusMessage,
                    false);
        }

        Bitmap resized = null;
        try {
            resized = Bitmap.createScaledBitmap(source, inputWidth, inputHeight, true);
            int[] pixels = new int[inputWidth * inputHeight];
            resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);

            float[][][][] input = new float[1][inputHeight][inputWidth][3];
            int pixelIndex = 0;
            for (int y = 0; y < inputHeight; y++) {
                for (int x = 0; x < inputWidth; x++) {
                    int color = pixels[pixelIndex++];
                    input[0][y][x][0] = Color.red(color) / 255.0f;
                    input[0][y][x][1] = Color.green(color) / 255.0f;
                    input[0][y][x][2] = Color.blue(color) / 255.0f;
                }
            }

            float[][][][] output = new float[1][outputHeight][outputWidth][outputClassCount];
            invokeRun(interpreter, input, output);
            validateProbabilityOutput(output);

            int total = outputWidth * outputHeight;
            int confidentVegetation = 0;
            int confidentNonVegetation = 0;
            int unknown = 0;
            double confidenceSum = 0.0d;
            int classifiedPixels = 0;
            int[] maskPixels = new int[total];
            float threshold = confidenceThreshold;

            for (int y = 0; y < outputHeight; y++) {
                for (int x = 0; x < outputWidth; x++) {
                    float[] scores = output[0][y][x];
                    int outputIndex = y * outputWidth + x;
                    float vegetationProbability;
                    float bestConfidence;
                    int bestClass;

                    if (singleChannelVegetationProbability) {
                        vegetationProbability = scores[0];
                        bestConfidence = Math.max(vegetationProbability, 1.0f - vegetationProbability);
                        if (vegetationProbability >= threshold) {
                            maskPixels[outputIndex] = Color.WHITE;
                            confidentVegetation++;
                            confidenceSum += vegetationProbability;
                            classifiedPixels++;
                        } else if (vegetationProbability <= 1.0f - threshold) {
                            maskPixels[outputIndex] = Color.BLACK;
                            confidentNonVegetation++;
                            confidenceSum += 1.0f - vegetationProbability;
                            classifiedPixels++;
                        } else {
                            maskPixels[outputIndex] = Color.TRANSPARENT;
                            unknown++;
                        }
                        continue;
                    }

                    bestClass = 0;
                    bestConfidence = scores[0];
                    float probabilitySum = 0.0f;
                    for (int c = 0; c < outputClassCount; c++) {
                        probabilitySum += scores[c];
                        if (scores[c] > bestConfidence) {
                            bestConfidence = scores[c];
                            bestClass = c;
                        }
                    }
                    if (probabilitySum <= 0.0f) {
                        maskPixels[outputIndex] = Color.TRANSPARENT;
                        unknown++;
                        continue;
                    }
                    bestConfidence = bestConfidence / probabilitySum;
                    if (bestConfidence < threshold) {
                        maskPixels[outputIndex] = Color.TRANSPARENT;
                        unknown++;
                        continue;
                    }

                    confidenceSum += bestConfidence;
                    classifiedPixels++;
                    if (bestClass == vegetationClassIndex) {
                        maskPixels[outputIndex] = Color.WHITE;
                        confidentVegetation++;
                    } else {
                        maskPixels[outputIndex] = Color.BLACK;
                        confidentNonVegetation++;
                    }
                }
            }

            Bitmap mask = Bitmap.createBitmap(
                    maskPixels, outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
            double vegetationFraction = total == 0
                    ? 0.0d : confidentVegetation / (double) total;
            double classifiedFraction = total == 0
                    ? 0.0d : classifiedPixels / (double) total;
            double meanConfidence = classifiedPixels == 0
                    ? 0.0d : confidenceSum / classifiedPixels;
            lastInferenceError = "";

            return ClassificationResult.success(
                    mask,
                    isModelValidated(),
                    outputWidth,
                    outputHeight,
                    total,
                    confidentVegetation,
                    confidentNonVegetation,
                    unknown,
                    vegetationFraction,
                    classifiedFraction,
                    meanConfidence,
                    confidenceThreshold,
                    labels
            );
        } catch (OutOfMemoryError e) {
            lastInferenceError = "OUT_OF_MEMORY_DURING_INFERENCE";
            Log.e(TAG, lastInferenceError, e);
            return ClassificationResult.failure(lastInferenceError, isModelValidated());
        } catch (Exception e) {
            lastInferenceError = rootMessage(e);
            Log.e(TAG, "Vegetation segmentation failed: " + lastInferenceError, e);
            return ClassificationResult.failure(lastInferenceError, isModelValidated());
        } finally {
            if (resized != null && resized != source && !resized.isRecycled()) {
                resized.recycle();
            }
        }
    }

    /**
     * Closes the interpreter. Calling initialize() again creates a fresh session
     * and clears validation approval, by design.
     */
    @Override
    public synchronized void close() {
        closeInterpreterQuietly();
        modelLoaded = false;
        validationApproved = false;
        validationEvidence = "";
        status = STATUS_CLOSED;
        statusMessage = "Classifier is closed.";
    }

    // ---------------------------------------------------------------------
    // Model and tensor validation
    // ---------------------------------------------------------------------

    private boolean failUnavailable(String message) {
        status = STATUS_UNAVAILABLE;
        statusMessage = message;
        modelLoaded = false;
        validationApproved = false;
        Log.w(TAG, message);
        return false;
    }

    private void validateInputShape(int[] shape, String dataType) {
        if (!"FLOAT32".equalsIgnoreCase(dataType)) {
            throw new IllegalArgumentException("INPUT_TYPE_UNSUPPORTED: expected FLOAT32, got " + dataType);
        }
        if (shape == null || shape.length != 4 || shape[0] != 1 || shape[3] != 3) {
            throw new IllegalArgumentException("INPUT_SHAPE_UNSUPPORTED: expected [1,height,width,3]");
        }
        validateSpatialSize(shape[2], shape[1], "INPUT");
    }

    private void validateOutputShape(int[] shape, String dataType) {
        if (!"FLOAT32".equalsIgnoreCase(dataType)) {
            throw new IllegalArgumentException("OUTPUT_TYPE_UNSUPPORTED: expected FLOAT32, got " + dataType);
        }
        if (shape == null || shape.length != 4 || shape[0] != 1
                || shape[3] < 1 || shape[3] > 256) {
            throw new IllegalArgumentException("OUTPUT_SHAPE_UNSUPPORTED: expected [1,height,width,classes<=256]");
        }
        validateSpatialSize(shape[2], shape[1], "OUTPUT");
        if ((long) shape[2] * (long) shape[1] * (long) shape[3] > 16_777_216L) {
            throw new IllegalArgumentException("OUTPUT_TENSOR_TOO_LARGE");
        }
    }

    private void validateSpatialSize(int width, int height, String name) {
        if (width <= 0 || height <= 0 || (long) width * (long) height > MAX_TENSOR_PIXELS) {
            throw new IllegalArgumentException(name + "_SIZE_UNSUPPORTED: " + width + "x" + height);
        }
    }

    private int[] tensorShape(Object interpreterObject, String getter, int index) throws Exception {
        Object tensor = invoke(interpreterObject, getter, new Class<?>[]{int.class}, index);
        Object shape = invoke(tensor, "shape", new Class<?>[0]);
        if (!(shape instanceof int[])) {
            throw new IllegalArgumentException("TENSOR_SHAPE_UNAVAILABLE: " + getter);
        }
        return (int[]) shape;
    }

    private String tensorDataType(Object interpreterObject, String getter, int index) throws Exception {
        Object tensor = invoke(interpreterObject, getter, new Class<?>[]{int.class}, index);
        Object dataType = invoke(tensor, "dataType", new Class<?>[0]);
        return dataType == null ? "UNKNOWN" : String.valueOf(dataType).toUpperCase(Locale.US);
    }

    private void invokeRun(Object interpreterObject, Object input, Object output) throws Exception {
        Method run = interpreterObject.getClass().getMethod("run", Object.class, Object.class);
        try {
            run.invoke(interpreterObject, input, output);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("TFLITE_INFERENCE_FAILED: " + rootMessage(cause), cause);
        }
    }

    private void validateProbabilityOutput(float[][][][] output) {
        for (int y = 0; y < outputHeight; y++) {
            for (int x = 0; x < outputWidth; x++) {
                float[] scores = output[0][y][x];
                float sum = 0.0f;
                for (int c = 0; c < outputClassCount; c++) {
                    float value = scores[c];
                    if (Float.isNaN(value) || Float.isInfinite(value)
                            || value < -PROBABILITY_EPSILON
                            || value > 1.0f + PROBABILITY_EPSILON) {
                        throw new IllegalArgumentException(
                                "MODEL_OUTPUT_NOT_PROBABILITIES: expected values in [0,1].");
                    }
                    scores[c] = Math.max(0.0f, Math.min(1.0f, value));
                    sum += scores[c];
                }
                if (sum <= 0.0f) {
                    // Leave all-zero output to be rendered as unknown.
                    continue;
                }
                if (!singleChannelVegetationProbability) {
                    for (int c = 0; c < outputClassCount; c++) {
                        scores[c] /= sum;
                    }
                }
            }
        }
    }

    private List<String> readLabels(AssetManager assets, String assetName) throws Exception {
        ArrayList<String> loaded = new ArrayList<>();
        try (InputStream input = assets.open(assetName);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                // Supports indexed labels such as "0 background" or "1,vegetation".
                int separator = firstLabelSeparator(value);
                if (separator > 0) {
                    String prefix = value.substring(0, separator).trim();
                    if (isDigits(prefix)) {
                        int declaredIndex = Integer.parseInt(prefix);
                        if (declaredIndex != loaded.size()) {
                            throw new IllegalArgumentException(
                                    "LABEL_INDEX_ORDER_INVALID at line: " + value);
                        }
                        value = value.substring(separator).trim();
                    }
                }
                if (value.isEmpty()) {
                    throw new IllegalArgumentException("EMPTY_LABEL_ENTRY");
                }
                loaded.add(value);
            }
        }
        return loaded;
    }

    private int findVegetationLabel(List<String> candidates) {
        for (int i = 0; i < candidates.size(); i++) {
            String normalized = normalizeLabel(candidates.get(i));
            if ("vegetation".equals(normalized)
                    || "plant".equals(normalized)
                    || "plants".equals(normalized)
                    || "foliage".equals(normalized)
                    || "leaf".equals(normalized)
                    || "leaves".equals(normalized)) {
                return i;
            }
        }
        return -1;
    }

    private String normalizeLabel(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    private int firstLabelSeparator(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == ':' || c == ',' || c == ';') {
                return i;
            }
        }
        return -1;
    }

    private boolean isDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private File copyAssetToCache(AssetManager assets, String assetName) throws Exception {
        File directory = new File(appContext.getCacheDir(), "landcam_vegetation_model");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("MODEL_CACHE_DIRECTORY_UNAVAILABLE");
        }
        String safeName = assetName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.isEmpty()) {
            safeName = DEFAULT_MODEL_ASSET;
        }
        File target = new File(directory, safeName);
        File temporary = File.createTempFile("model_", ".tmp", directory);
        try (InputStream input = assets.open(assetName);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw e;
        }
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new IllegalStateException("MODEL_CACHE_REPLACE_FAILED");
        }
        if (!temporary.renameTo(target)) {
            // Fallback for filesystems that do not support rename across names.
            try (InputStream input = new java.io.FileInputStream(temporary);
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            } finally {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
        }
        if (!target.isFile() || target.length() <= 0L) {
            throw new IllegalStateException("MODEL_CACHE_COPY_FAILED");
        }
        return target;
    }

    private String requireAssetName(String value, String argumentName) {
        if (value == null || value.trim().isEmpty()
                || value.startsWith("/") || value.contains("..")) {
            throw new IllegalArgumentException(argumentName + " is invalid");
        }
        return value.trim();
    }

    private Object invoke(
            Object target,
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Exception {
        Method method = target.getClass().getMethod(methodName, parameterTypes);
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(rootMessage(cause), cause);
        }
    }

    private void closeInterpreterQuietly() {
        Object current = interpreter;
        interpreter = null;
        if (current != null) {
            closeObjectQuietly(current);
        }
        modelLoaded = false;
    }

    private void closeObjectQuietly(Object instance) {
        if (instance == null) {
            return;
        }
        try {
            Method close = instance.getClass().getMethod("close");
            close.invoke(instance);
        } catch (Exception e) {
            Log.w(TAG, "Interpreter close warning: " + rootMessage(e));
        }
    }

    private static String rootMessage(Throwable error) {
        if (error == null) {
            return "UNKNOWN_ERROR";
        }
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return current.getClass().getSimpleName();
        }
        return message;
    }

    // ---------------------------------------------------------------------
    // Result value type
    // ---------------------------------------------------------------------

    public static final class ClassificationResult {
        private final boolean successful;
        private final String error;
        private final Bitmap reviewMask;
        private final boolean modelValidated;
        private final int width;
        private final int height;
        private final int totalPixels;
        private final int vegetationPixels;
        private final int nonVegetationPixels;
        private final int unknownPixels;
        private final double vegetationFraction;
        private final double classifiedFraction;
        private final double meanConfidence;
        private final float confidenceThreshold;
        private final List<String> labels;

        private ClassificationResult(
                boolean successful,
                String error,
                Bitmap reviewMask,
                boolean modelValidated,
                int width,
                int height,
                int totalPixels,
                int vegetationPixels,
                int nonVegetationPixels,
                int unknownPixels,
                double vegetationFraction,
                double classifiedFraction,
                double meanConfidence,
                float confidenceThreshold,
                List<String> labels
        ) {
            this.successful = successful;
            this.error = error == null ? "" : error;
            this.reviewMask = reviewMask;
            this.modelValidated = modelValidated;
            this.width = width;
            this.height = height;
            this.totalPixels = totalPixels;
            this.vegetationPixels = vegetationPixels;
            this.nonVegetationPixels = nonVegetationPixels;
            this.unknownPixels = unknownPixels;
            this.vegetationFraction = vegetationFraction;
            this.classifiedFraction = classifiedFraction;
            this.meanConfidence = meanConfidence;
            this.confidenceThreshold = confidenceThreshold;
            this.labels = labels == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(labels));
        }

        private static ClassificationResult success(
                Bitmap mask,
                boolean validated,
                int width,
                int height,
                int total,
                int vegetation,
                int nonVegetation,
                int unknown,
                double vegetationFraction,
                double classifiedFraction,
                double meanConfidence,
                float threshold,
                List<String> labels
        ) {
            return new ClassificationResult(
                    true, "", mask, validated, width, height, total, vegetation,
                    nonVegetation, unknown, vegetationFraction, classifiedFraction,
                    meanConfidence, threshold, labels);
        }

        private static ClassificationResult failure(String error, boolean validated) {
            return new ClassificationResult(
                    false, error, null, validated, 0, 0, 0, 0, 0, 0,
                    0.0d, 0.0d, 0.0d, DEFAULT_CONFIDENCE_THRESHOLD,
                    Collections.emptyList());
        }

        public boolean isSuccessful() {
            return successful;
        }

        public String getError() {
            return error;
        }

        /**
         * Returns the mask only when external validation has been approved.
         * White=vegetation, black=confident non-vegetation, transparent=unknown.
         * The returned Bitmap is owned by the caller and must be recycled.
         */
        public Bitmap getVegetationMask() {
            return successful && modelValidated ? reviewMask : null;
        }

        /** Returns the unvalidated prediction mask for debugging/model review only. */
        public Bitmap getReviewMask() {
            return successful ? reviewMask : null;
        }

        public boolean isModelValidated() {
            return modelValidated;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public int getTotalPixels() {
            return totalPixels;
        }

        public int getVegetationPixels() {
            return vegetationPixels;
        }

        public int getNonVegetationPixels() {
            return nonVegetationPixels;
        }

        public int getUnknownPixels() {
            return unknownPixels;
        }

        /** Fraction of the full output grid confidently predicted as vegetation. */
        public double getVegetationFraction() {
            return vegetationFraction;
        }

        public double getClassifiedFraction() {
            return classifiedFraction;
        }

        public double getMeanConfidence() {
            return meanConfidence;
        }

        public float getConfidenceThreshold() {
            return confidenceThreshold;
        }

        public List<String> getLabels() {
            return labels;
        }

        public Map<String, Object> toMap() {
            HashMap<String, Object> map = new HashMap<>();
            map.put("successful", successful);
            map.put("error", error);
            map.put("modelValidated", modelValidated);
            map.put("maskAvailable", successful && modelValidated && reviewMask != null);
            map.put("width", width);
            map.put("height", height);
            map.put("totalPixels", totalPixels);
            map.put("vegetationPixels", vegetationPixels);
            map.put("nonVegetationPixels", nonVegetationPixels);
            map.put("unknownPixels", unknownPixels);
            map.put("vegetationFraction", vegetationFraction);
            map.put("classifiedFraction", classifiedFraction);
            map.put("meanConfidence", meanConfidence);
            map.put("confidenceThreshold", confidenceThreshold);
            map.put("labels", new ArrayList<>(labels));
            map.put("meaning", "SEMANTIC_CLASS_ONLY_NOT_VEGETATION_HEALTH_OR_NDVI");
            return map;
        }
    }
}
