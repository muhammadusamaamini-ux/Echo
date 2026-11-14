package com.usama.echo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.RgbAdjustment;
import androidx.media3.effect.RgbFilter;
import androidx.media3.effect.RgbMatrix;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.SpeedChangeEffect;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;

import com.google.common.collect.ImmutableList;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Everything about a video being edited, and how it becomes a Media3 Transformer "Composition". */
final class EditProject {

    static final class Clip {
        Uri uri;
        String name;
        boolean image;
        long sourceMs;          // full length of the file (photos: 0)
        long startMs, endMs;    // trimmed part
        long photoMs = 3000;    // how long a photo shows
        float speed = 1f, volume = 1f, pitch = 1f;
        int width, height;      // as shown (rotation already applied)
        Bitmap thumb;

        long lengthMs() {
            long raw = image ? photoMs : Math.max(0, endMs - startMs);
            return (long) (raw / speed);
        }

        Clip copy() {
            Clip c = new Clip();
            c.uri = uri; c.name = name; c.image = image; c.sourceMs = sourceMs; c.startMs = startMs; c.endMs = endMs;
            c.photoMs = photoMs; c.speed = speed; c.volume = volume; c.pitch = pitch; c.width = width;
            c.height = height; c.thumb = thumb;
            return c;
        }
    }

    static final class TextItem {
        String text = "";
        int color = Color.WHITE;
        float size = 0.07f;   // text height as a part of the video height
        float x = 0.5f, y = 0.85f;
        boolean background, bold = true;
    }

    final List<Clip> clips = new ArrayList<>();
    final List<TextItem> texts = new ArrayList<>();
    int rotate;                 // 0, 90, 180, 270
    boolean flipH, flipV;
    String filter = "None";
    float brightness, contrast, saturation; // -100..100
    float aspect;               // 0 = original
    boolean aspectFill = true;  // true = crop to fill, false = fit with black bars
    int quality = 1080;         // short side in pixels; 0 = original
    Uri sticker;
    float stickerX = 0.82f, stickerY = 0.15f, stickerSize = 0.22f;
    Uri music;
    String musicName;
    float musicVolume = 0.8f, originalVolume = 1f;

    static final String[] FILTERS = {"None", "Black & White", "Sepia", "Vintage", "Warm", "Cool", "Vivid",
            "Dramatic", "Noir", "Invert"};

    long totalMs() {
        long t = 0;
        for (Clip c : clips) t += c.lengthMs();
        return t;
    }

    // ---------- reading a file ----------

    /** Reads a video's or photo's size and length. Call from a background thread. */
    static Clip probe(Context ctx, Uri uri, String name, String mime) {
        Clip c = new Clip();
        c.uri = uri;
        c.name = name;
        c.image = mime != null && mime.startsWith("image/");
        if (c.image) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            } catch (Exception ignored) {
            }
            c.width = o.outWidth > 0 ? o.outWidth : 1080;
            c.height = o.outHeight > 0 ? o.outHeight : 1920;
            BitmapFactory.Options t = new BitmapFactory.Options();
            t.inSampleSize = Math.max(1, Math.min(c.width, c.height) / 160);
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                c.thumb = BitmapFactory.decodeStream(in, null, t);
            } catch (Exception ignored) {
            }
            return c;
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            c.sourceMs = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int w = (int) parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = (int) parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rot = (int) parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            boolean swap = rot == 90 || rot == 270;
            c.width = swap ? h : w;
            c.height = swap ? w : h;
            Bitmap f = r.getFrameAtTime(Math.min(1_000_000L, c.sourceMs * 500), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (f != null) {
                int tw = 200, th = Math.max(1, f.getHeight() * tw / Math.max(1, f.getWidth()));
                c.thumb = Bitmap.createScaledBitmap(f, tw, th, true);
                if (c.thumb != f) f.recycle();
            }
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        if (c.width <= 0 || c.height <= 0) {
            c.width = 1280;
            c.height = 720;
        }
        c.startMs = 0;
        c.endMs = c.sourceMs;
        return c;
    }

    private static long parse(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- output size ----------

    /** Width and height of the finished video (even numbers). */
    int[] outSize() {
        Clip first = clips.isEmpty() ? null : clips.get(0);
        int w = first == null ? 1280 : first.width, h = first == null ? 720 : first.height;
        if (rotate % 180 != 0) {
            int t = w; w = h; h = t;
        }
        if (aspect > 0) {
            float cur = (float) w / h;
            if (aspectFill) {
                if (cur > aspect) w = Math.round(h * aspect);
                else h = Math.round(w / aspect);
            } else {
                if (cur > aspect) h = Math.round(w / aspect);
                else w = Math.round(h * aspect);
            }
        }
        float scale = 1f;
        int shortSide = Math.min(w, h);
        if (quality > 0 && shortSide > quality) scale = (float) quality / shortSide;
        int longSide = Math.max(w, h);
        if (longSide * scale > 3840) scale = 3840f / longSide;
        w = Math.max(2, Math.round(w * scale) / 2 * 2);
        h = Math.max(2, Math.round(h * scale) / 2 * 2);
        return new int[]{w, h};
    }

    // ---------- effects ----------

    /** The picture effects (without speed), shared by the preview and the export. */
    List<Effect> pictureEffects(Context ctx, boolean withOverlay) {
        List<Effect> e = new ArrayList<>();
        if (rotate != 0 || flipH || flipV) {
            e.add(new ScaleAndRotateTransformation.Builder()
                    .setRotationDegrees(-rotate) // clockwise
                    .setScale(flipH ? -1f : 1f, flipV ? -1f : 1f)
                    .build());
        }
        addFilter(e);
        if (brightness != 0) e.add(new Brightness(brightness / 100f * 0.5f));
        if (contrast != 0) e.add(new Contrast(contrast / 100f));
        if (saturation != 0) e.add(new HslAdjustment.Builder().adjustSaturation(saturation).build());
        int[] size = outSize();
        e.add(Presentation.createForWidthAndHeight(size[0], size[1],
                aspectFill ? Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP : Presentation.LAYOUT_SCALE_TO_FIT));
        if (withOverlay) {
            Bitmap overlay = overlayBitmap(ctx, size[0], size[1]);
            if (overlay != null) {
                TextureOverlay o = BitmapOverlay.createStaticBitmapOverlay(overlay);
                e.add(new OverlayEffect(ImmutableList.of(o)));
            }
        }
        return e;
    }

    private void addFilter(List<Effect> e) {
        switch (filter) {
            case "Black & White": e.add(RgbFilter.createGrayscaleFilter()); break;
            case "Invert": e.add(RgbFilter.createInvertedFilter()); break;
            case "Sepia": e.add(matrix(new float[]{
                    0.393f, 0.349f, 0.272f, 0,
                    0.769f, 0.686f, 0.534f, 0,
                    0.189f, 0.168f, 0.131f, 0,
                    0, 0, 0, 1})); break;
            case "Vintage":
                e.add(new RgbAdjustment.Builder().setRedScale(1.08f).setGreenScale(1.0f).setBlueScale(0.82f).build());
                e.add(new HslAdjustment.Builder().adjustSaturation(-30).build());
                e.add(new Contrast(-0.15f));
                break;
            case "Warm": e.add(new RgbAdjustment.Builder().setRedScale(1.12f).setGreenScale(1.03f).setBlueScale(0.88f).build()); break;
            case "Cool": e.add(new RgbAdjustment.Builder().setRedScale(0.9f).setGreenScale(1.0f).setBlueScale(1.15f).build()); break;
            case "Vivid":
                e.add(new HslAdjustment.Builder().adjustSaturation(35).build());
                e.add(new Contrast(0.12f));
                break;
            case "Dramatic":
                e.add(new Contrast(0.35f));
                e.add(new HslAdjustment.Builder().adjustSaturation(-15).build());
                break;
            case "Noir":
                e.add(RgbFilter.createGrayscaleFilter());
                e.add(new Contrast(0.4f));
                break;
            default: break;
        }
    }

    /** A colour matrix (column-major 4×4). */
    private static RgbMatrix matrix(final float[] m) {
        return new RgbMatrix() {
            @Override
            public float[] getMatrix(long presentationTimeUs, boolean useHdr) {
                return m;
            }
        };
    }

    /** Texts and the sticker drawn on one transparent picture the size of the video. */
    Bitmap overlayBitmap(Context ctx, int w, int h) {
        if (texts.isEmpty() && sticker == null) return null;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        if (sticker != null) {
            try (InputStream in = ctx.getContentResolver().openInputStream(sticker)) {
                Bitmap s = BitmapFactory.decodeStream(in);
                if (s != null) {
                    float sw = stickerSize * w, sh = sw * s.getHeight() / Math.max(1, s.getWidth());
                    float cx = stickerX * w, cy = stickerY * h;
                    cv.drawBitmap(s, null, new RectF(cx - sw / 2, cy - sh / 2, cx + sw / 2, cy + sh / 2),
                            new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
                    s.recycle();
                }
            } catch (Exception ignored) {
            }
        }
        for (TextItem t : texts) {
            if (t.text.trim().isEmpty()) continue;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(t.color);
            p.setTextSize(t.size * h);
            p.setTypeface(t.bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            p.setTextAlign(Paint.Align.CENTER);
            if (!t.background) p.setShadowLayer(t.size * h * 0.12f, 0, t.size * h * 0.05f, 0xCC000000);
            String[] lines = t.text.split("\n");
            Paint.FontMetrics fm = p.getFontMetrics();
            float lineH = fm.descent - fm.ascent;
            float blockH = lineH * lines.length;
            float top = t.y * h - blockH / 2;
            if (t.background) {
                float maxW = 0;
                for (String l : lines) maxW = Math.max(maxW, p.measureText(l));
                float pad = t.size * h * 0.35f;
                Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
                bg.setColor(t.color == Color.BLACK ? 0xDDFFFFFF : 0xB3000000);
                cv.drawRoundRect(new RectF(t.x * w - maxW / 2 - pad, top - pad * 0.6f, t.x * w + maxW / 2 + pad,
                        top + blockH + pad * 0.6f), pad, pad, bg);
            }
            for (int i = 0; i < lines.length; i++) {
                cv.drawText(lines[i], t.x * w, top + i * lineH - fm.ascent, p);
            }
        }
        return b;
    }

    // ---------- export ----------

    private List<AudioProcessor> audioFor(Clip c) {
        List<AudioProcessor> a = new ArrayList<>();
        if (c.speed != 1f || c.pitch != 1f) {
            SonicAudioProcessor s = new SonicAudioProcessor();
            s.setSpeed(c.speed);
            s.setPitch(c.pitch);
            a.add(s);
        }
        float vol = c.volume * originalVolume;
        if (vol != 1f) {
            ChannelMixingAudioProcessor m = new ChannelMixingAudioProcessor();
            m.putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(vol));
            m.putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(vol));
            a.add(m);
        }
        return a;
    }

    /** Builds what Transformer exports. audioOnly = just the sound (for "Extract audio"). */
    Composition composition(Context ctx, boolean audioOnly) {
        List<Effect> picture = audioOnly ? new ArrayList<>() : pictureEffects(ctx, true);
        List<EditedMediaItem> items = new ArrayList<>();
        for (Clip c : clips) {
            if (audioOnly && c.image) continue;
            List<Effect> video = new ArrayList<>();
            if (!audioOnly && c.speed != 1f && !c.image) video.add(new SpeedChangeEffect(c.speed));
            video.addAll(picture);
            EditedMediaItem.Builder b;
            if (c.image) {
                b = new EditedMediaItem.Builder(new MediaItem.Builder().setUri(c.uri)
                        .setImageDurationMs(c.photoMs).build())
                        .setDurationUs(c.photoMs * 1000)
                        .setFrameRate(30);
            } else {
                MediaItem mi = new MediaItem.Builder().setUri(c.uri)
                        .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(c.startMs)
                                .setEndPositionMs(c.endMs > c.startMs ? c.endMs : C_TIME_END)
                                .build())
                        .build();
                b = new EditedMediaItem.Builder(mi);
            }
            b.setEffects(new Effects(audioFor(c), video));
            if (audioOnly) b.setRemoveVideo(true);
            items.add(b.build());
        }
        List<EditedMediaItemSequence> seqs = new ArrayList<>();
        seqs.add(new EditedMediaItemSequence(items));
        if (music != null) {
            List<AudioProcessor> mp = new ArrayList<>();
            if (musicVolume != 1f) {
                ChannelMixingAudioProcessor m = new ChannelMixingAudioProcessor();
                m.putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(musicVolume));
                m.putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(musicVolume));
                mp.add(m);
            }
            EditedMediaItem song = new EditedMediaItem.Builder(MediaItem.fromUri(music))
                    .setRemoveVideo(true)
                    .setEffects(new Effects(mp, ImmutableList.of()))
                    .build();
            seqs.add(new EditedMediaItemSequence(ImmutableList.of(song), true)); // repeats until the video ends
        }
        return new Composition.Builder(seqs)
                .experimentalSetForceAudioTrack(true)
                .build();
    }

    private static final long C_TIME_END = Long.MIN_VALUE; // C.TIME_END_OF_SOURCE
}
