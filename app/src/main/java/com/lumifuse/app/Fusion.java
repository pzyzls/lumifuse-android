package com.lumifuse.app;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.net.Uri;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.MergeMertens;
import org.opencv.photo.Photo;
import org.opencv.video.Video;
import java.util.ArrayList;
import java.util.List;

/** 合成核心：缩略图 ECC 仿射配准，HDR 多尺度曝光融合 / 清晰度加权景深融合。 */
final class Fusion {
    interface Progress { void update(String message); }
    private Fusion() {}

    static Bitmap merge(ContentResolver resolver, List<Uri> uris, int edge,
                        boolean hdr, boolean align, Progress progress) throws Exception {
        if (uris.size() < 2 || uris.size() > 7) throw new IllegalArgumentException("需要 2～7 张照片");
        List<Mat> frames = new ArrayList<>();
        Mat reference = new Mat(), coverage = new Mat();
        Mat weighted = new Mat(), weights = new Mat(), output = new Mat();
        int width = 0, height = 0;
        try {
            for (int index = 0; index < uris.size(); index++) {
                checkCancelled();
                progress.update("读取和对齐 " + (index + 1) + " / " + uris.size());
                Bitmap bitmap = ImageLoader.load(resolver, uris.get(index), edge);
                Mat frame = new Mat();
                try {
                    Utils.bitmapToMat(bitmap, frame);
                    Imgproc.cvtColor(frame, frame, Imgproc.COLOR_RGBA2RGB);
                    if (index == 0) {
                        width = frame.cols(); height = frame.rows();
                        if (width < 32 || height < 32) throw new IllegalArgumentException("照片尺寸过小");
                        frame.copyTo(reference);
                        coverage = replace(coverage, new Mat(height, width, CvType.CV_8UC1, Scalar.all(255)));
                    } else {
                        if (frame.cols() != width || frame.rows() != height) {
                            throw new IllegalArgumentException("请使用相同尺寸、方向的包围照片");
                        }
                        if (align) align(reference, frame, coverage);
                    }
                    if (hdr) {
                        frames.add(frame);
                        frame = null;
                    } else {
                        accumulateFocus(frame, weighted, weights);
                    }
                } finally {
                    bitmap.recycle();
                    if (frame != null) frame.release();
                }
            }
            reference.release();
            checkCancelled();
            progress.update(hdr ? "多尺度曝光融合…" : "融合清晰区域…");
            if (hdr) {
                MergeMertens merger = Photo.createMergeMertens(1f, 1f, 1f);
                try {
                    merger.process(frames, output);
                    // 曝光融合结果为 0～1 浮点 RGB，不是 HDR DNG。
                    output.convertTo(output, CvType.CV_8UC3, 255);
                } finally { merger.clear(); }
            } else {
                Mat threeWeights = new Mat();
                try {
                    Core.merge(java.util.Arrays.asList(weights, weights, weights), threeWeights);
                    Core.divide(weighted, threeWeights, output);
                    output.convertTo(output, CvType.CV_8UC3);
                } finally { threeWeights.release(); }
            }
            checkCancelled();
            Rect crop = largestValidRectangle(coverage);
            if ((long) crop.width * crop.height < (long) width * height / 2) {
                throw new IllegalArgumentException("有效重叠区域过小，请使用同一组固定机位照片");
            }
            Mat cropped = output.submat(crop), rgba = new Mat();
            try {
                Imgproc.cvtColor(cropped, rgba, Imgproc.COLOR_RGB2RGBA);
                Bitmap result = Bitmap.createBitmap(crop.width, crop.height, Bitmap.Config.ARGB_8888);
                Utils.matToBitmap(rgba, result);
                return result;
            } finally { cropped.release(); rgba.release(); }
        } finally {
            for (Mat frame : frames) frame.release();
            reference.release(); coverage.release(); weighted.release(); weights.release(); output.release();
        }
    }

    private static Mat replace(Mat old, Mat replacement) { old.release(); return replacement; }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("已取消处理");
    }

    private static void align(Mat reference, Mat frame, Mat coverage) {
        Mat ref = new Mat(), current = new Mat(), transform = Mat.eye(2, 3, CvType.CV_32F);
        Mat warped = new Mat(), mask = new Mat(), valid = new Mat();
        try {
            double scale = Math.min(1.0, 640.0 / Math.max(reference.cols(), reference.rows()));
            Size small = new Size(Math.round(reference.cols() * scale), Math.round(reference.rows() * scale));
            Imgproc.resize(reference, ref, small); Imgproc.resize(frame, current, small);
            Imgproc.cvtColor(ref, ref, Imgproc.COLOR_RGB2GRAY);
            Imgproc.cvtColor(current, current, Imgproc.COLOR_RGB2GRAY);
            Imgproc.equalizeHist(ref, ref); Imgproc.equalizeHist(current, current);
            double score;
            try {
                score = Video.findTransformECC(ref, current, transform, Video.MOTION_AFFINE,
                        new TermCriteria(TermCriteria.COUNT | TermCriteria.EPS, 80, 0.0001));
            } catch (CvException error) {
                throw new IllegalArgumentException("自动对齐失败：请重选同组照片，或三脚架拍摄后关闭对齐", error);
            }
            if (!Double.isFinite(score) || score < 0.5) {
                throw new IllegalArgumentException("照片匹配度过低，请检查所选照片是否为同一组");
            }
            transform.put(0, 2, (float) (transform.get(0, 2)[0] * frame.cols() / small.width));
            transform.put(1, 2, (float) (transform.get(1, 2)[0] * frame.rows() / small.height));
            Imgproc.warpAffine(frame, warped, transform, reference.size(),
                    Imgproc.INTER_LINEAR | Imgproc.WARP_INVERSE_MAP, Core.BORDER_CONSTANT, Scalar.all(0));
            mask = replace(mask, new Mat(frame.rows(), frame.cols(), CvType.CV_8UC1, Scalar.all(255)));
            Imgproc.warpAffine(mask, valid, transform, reference.size(),
                    Imgproc.INTER_LINEAR | Imgproc.WARP_INVERSE_MAP, Core.BORDER_CONSTANT, Scalar.all(0));
            Imgproc.threshold(valid, valid, 254, 255, Imgproc.THRESH_BINARY);
            Core.bitwise_and(coverage, valid, coverage);
            warped.copyTo(frame);
        } finally {
            ref.release(); current.release(); transform.release(); warped.release(); mask.release(); valid.release();
        }
    }

    private static void accumulateFocus(Mat frame, Mat sum, Mat weightSum) {
        Mat gray = new Mat(), laplacian = new Mat(), weight = new Mat();
        Mat rgbFloat = new Mat(), rgbWeight = new Mat();
        try {
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGB2GRAY);
            gray.convertTo(gray, CvType.CV_32F, 1.0 / 255);
            Imgproc.Laplacian(gray, laplacian, CvType.CV_32F, 3);
            Core.multiply(laplacian, laplacian, weight);
            // 对局部清晰度平滑，缓解逐像素择优产生的接缝；仍可能出现遮挡光晕。
            Imgproc.GaussianBlur(weight, weight, new Size(9, 9), 0);
            Core.add(weight, Scalar.all(0.00001), weight);
            Core.merge(java.util.Arrays.asList(weight, weight, weight), rgbWeight);
            frame.convertTo(rgbFloat, CvType.CV_32FC3);
            Core.multiply(rgbFloat, rgbWeight, rgbFloat);
            if (sum.empty()) { rgbFloat.copyTo(sum); weight.copyTo(weightSum); }
            else { Core.add(sum, rgbFloat, sum); Core.add(weightSum, weight, weightSum); }
        } finally {
            gray.release(); laplacian.release(); weight.release(); rgbFloat.release(); rgbWeight.release();
        }
    }

    /** 单调栈求公共有效区域内最大矩形，裁掉仿射配准带来的黑边。 */
    private static Rect largestValidRectangle(Mat mask) {
        int width = mask.cols(), height = mask.rows();
        int[] heights = new int[width + 1], stack = new int[width + 1];
        byte[] row = new byte[width];
        Rect best = new Rect(); long bestArea = 0;
        for (int y = 0; y < height; y++) {
            mask.get(y, 0, row);
            for (int x = 0; x < width; x++) heights[x] = row[x] == (byte) 255 ? heights[x] + 1 : 0;
            int top = -1;
            for (int x = 0; x <= width; x++) {
                while (top >= 0 && heights[stack[top]] > heights[x]) {
                    int h = heights[stack[top--]], left = top < 0 ? 0 : stack[top] + 1;
                    long area = (long) h * (x - left);
                    if (area > bestArea) {
                        bestArea = area; best = new Rect(left, y - h + 1, x - left, h);
                    }
                }
                stack[++top] = x;
            }
        }
        return best;
    }
}
