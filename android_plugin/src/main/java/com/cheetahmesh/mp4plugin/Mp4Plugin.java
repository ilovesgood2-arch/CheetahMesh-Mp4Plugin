package com.cheetahmesh.mp4plugin;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.godotengine.godot.Godot;
import org.godotengine.godot.plugin.GodotPlugin;
import org.godotengine.godot.plugin.UsedByGodot;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;

public class Mp4Plugin extends GodotPlugin {

    public Mp4Plugin(Godot godot) {
        super(godot);
    }

    @Override
    public String getPluginName() {
        return "Mp4Plugin";
    }

    @UsedByGodot
    public String convertFramesToMp4(
            String framesDir,
            String outputPath,
            int fps,
            int width,
            int height,
            int crf
    ) {
        width  = (width  % 2 == 0) ? width  : width  - 1;
        height = (height % 2 == 0) ? height : height - 1;
        if (width <= 0 || height <= 0)
            return "ERROR: invalid dimensions " + width + "x" + height;

        File dir = new File(framesDir);
        if (!dir.exists() || !dir.isDirectory())
            return "ERROR: frames directory not found: " + framesDir;

        File[] frameFiles = dir.listFiles((d, name) ->
                name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".jpeg"));

        if (frameFiles == null || frameFiles.length == 0)
            return "ERROR: no JPG frames found in " + framesDir;

        Arrays.sort(frameFiles, Comparator.comparing(File::getName));

        int bitrate = (int)(8_000_000 * Math.pow(0.75, (crf - 18) / 5.0));
        bitrate = Math.max(bitrate, 500_000);

        MediaMuxer muxer = null;
        MediaCodec codec = null;

        try {
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE,   bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            File outFile = new File(outputPath);
            if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            long frameDurationUs = 1_000_000L / fps;
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int videoTrackIndex = -1;
            long presentationTimeUs = 0;

            for (int i = 0; i <= frameFiles.length; i++) {
                boolean endOfStream = (i == frameFiles.length);

                if (!endOfStream) {
                    Bitmap bmp = BitmapFactory.decodeFile(frameFiles[i].getAbsolutePath());
                    if (bmp == null) continue;
                    if (bmp.getWidth() != width || bmp.getHeight() != height)
                        bmp = Bitmap.createScaledBitmap(bmp, width, height, true);

                    int inputIndex = codec.dequeueInputBuffer(10_000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        inputBuffer.clear();
                        inputBuffer.put(bitmapToNv12(bmp, width, height));
                        codec.queueInputBuffer(inputIndex, 0, inputBuffer.position(),
                                presentationTimeUs, 0);
                        presentationTimeUs += frameDurationUs;
                    }
                    bmp.recycle();
                } else {
                    int inputIndex = codec.dequeueInputBuffer(10_000);
                    if (inputIndex >= 0)
                        codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }

                boolean outputDone = false;
                while (!outputDone) {
                    int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000);
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrackIndex = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                    } else if (outputIndex >= 0) {
                        ByteBuffer encodedData = codec.getOutputBuffer(outputIndex);
                        if (encodedData != null && bufferInfo.size > 0
                                && videoTrackIndex >= 0
                                && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            encodedData.position(bufferInfo.offset);
                            encodedData.limit(bufferInfo.offset + bufferInfo.size);
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
                        }
                        codec.releaseOutputBuffer(outputIndex, false);
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                            outputDone = true;
                    } else {
                        outputDone = true;
                    }
                }
                if (endOfStream) break;
            }

            codec.stop(); codec.release();
            muxer.stop(); muxer.release();
            return "OK:" + outputPath;

        } catch (Exception e) {
            if (codec != null) try { codec.release(); } catch (Exception ignored) {}
            if (muxer != null) try { muxer.release(); } catch (Exception ignored) {}
            new File(outputPath).delete();
            return "ERROR: " + e.getMessage();
        }
    }

    private byte[] bitmapToNv12(Bitmap bmp, int width, int height) {
        int[] argb = new int[width * height];
        bmp.getPixels(argb, 0, width, 0, 0, width, height);
        byte[] nv12 = new byte[width * height * 3 / 2];
        int uvOffset = width * height;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int pixel = argb[j * width + i];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >>  8) & 0xFF;
                int b =  pixel        & 0xFF;
                int y =  ((66*r + 129*g +  25*b + 128) >> 8) + 16;
                int u = ((-38*r -  74*g + 112*b + 128) >> 8) + 128;
                int v = ((112*r -  94*g -  18*b + 128) >> 8) + 128;
                nv12[j * width + i] = (byte) clamp(y, 0, 255);
                if ((j % 2 == 0) && (i % 2 == 0)) {
                    int uvIdx = uvOffset + (j/2) * width + i;
                    nv12[uvIdx]     = (byte) clamp(u, 0, 255);
                    nv12[uvIdx + 1] = (byte) clamp(v, 0, 255);
                }
            }
        }
        return nv12;
    }

    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
          }
