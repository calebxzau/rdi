/*
 * rdimedia: flat entry points to the two encoders of the modpack upload, for the launcher that
 * has no JVM (client/slint-proto, upload::media). Built by build-natives.sh against the FFmpeg
 * libraries of the same jar and packed next to them.
 *
 * Both functions do what the Kotlin encoders do, call for call, so an upload from either
 * launcher gives the same files:
 *   rdi_ogg_to_opus   OggTranscoder with AudioFrameReader, Pcm16Converter and OggOpusWriter
 *   rdi_rgba_to_avif  FfmpegAvifEncoder.encodeRgba
 * Change them together.
 */
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/channel_layout.h>
#include <libavutil/mem.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#if defined(_WIN32)
#define RDI_EXPORT __declspec(dllexport)
#else
#define RDI_EXPORT __attribute__((visibility("default")))
#endif

/* Raised when a function's meaning or arguments change. */
#define RDI_MEDIA_ABI 1

/* Results of rdi_ogg_to_opus and rdi_rgba_to_avif. */
#define RDI_OK 0
/* The sound is longer than the limit; no output. */
#define RDI_TOO_LONG 1
/* The input cannot be used (not a sound, damaged, no frames). The encoder is fine. */
#define RDI_BAD_INPUT 2
/* The encoder or the output failed, whatever the input: the library is not usable. */
#define RDI_ENCODER_FAILED 3

#define OPUS_SAMPLE_RATE 24000
#define OPUS_BITRATE 48000
#define OPUS_CHANNELS 2

#define AVIF_COLOR_CRF 30
#define AVIF_ALPHA_CRF 0

static void say(char *message, size_t size, const char *format, ...) {
    if (!message || size == 0) return;
    va_list arguments;
    va_start(arguments, format);
    vsnprintf(message, size, format, arguments);
    va_end(arguments);
}

static void say_av(char *message, size_t size, const char *operation, int code) {
    char text[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(code, text, sizeof text);
    say(message, size, "%s: %s (%d)", operation, text, code);
}

RDI_EXPORT int rdi_media_abi(void) {
    return RDI_MEDIA_ABI;
}

RDI_EXPORT void rdi_media_free(uint8_t *output) {
    av_free(output);
}

/* ---- Sound: any Ogg audio to Ogg Opus, 24 kHz stereo, 48 kbit/s ---- */

typedef struct OpusWriter {
    AVFormatContext *format;
    AVCodecContext *encoder;
    AVStream *stream;
    AVAudioFifo *queue;
    AVFrame *frame;
    AVPacket *packet;
    int frame_samples;
    int64_t next_pts;
} OpusWriter;

static int writer_send(OpusWriter *writer, AVFrame *input) {
    int result = avcodec_send_frame(writer->encoder, input);
    if (result < 0) return result;
    for (;;) {
        result = avcodec_receive_packet(writer->encoder, writer->packet);
        if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return 0;
        if (result < 0) return result;
        writer->packet->stream_index = writer->stream->index;
        /* The muxer may have changed the stream's time base while writing the header. */
        av_packet_rescale_ts(writer->packet, writer->encoder->time_base, writer->stream->time_base);
        result = av_interleaved_write_frame(writer->format, writer->packet);
        if (result < 0) return result;
    }
}

static int writer_encode_queued(OpusWriter *writer, int samples) {
    int result = av_frame_make_writable(writer->frame);
    if (result < 0) return result;
    writer->frame->nb_samples = samples;
    if (av_audio_fifo_read(writer->queue, (void **)writer->frame->data, samples) != samples) return AVERROR(EIO);
    writer->frame->pts = writer->next_pts;
    writer->next_pts += samples;
    return writer_send(writer, writer->frame);
}

/* libopus only takes frames of an exact length, so samples wait in a queue until a frame is full. */
static int writer_write(OpusWriter *writer, uint8_t **planes, int samples) {
    if (samples == 0) return 0;
    if (av_audio_fifo_write(writer->queue, (void **)planes, samples) != samples) return AVERROR(ENOMEM);
    while (av_audio_fifo_size(writer->queue) >= writer->frame_samples) {
        int result = writer_encode_queued(writer, writer->frame_samples);
        if (result < 0) return result;
    }
    return 0;
}

static int writer_finish(OpusWriter *writer) {
    int remaining = av_audio_fifo_size(writer->queue);
    if (remaining > 0) {
        int result = writer_encode_queued(writer, remaining);
        if (result < 0) return result;
    }
    int result = writer_send(writer, NULL);
    if (result < 0) return result;
    return av_write_trailer(writer->format);
}

static void writer_close(OpusWriter *writer) {
    av_packet_free(&writer->packet);
    av_frame_free(&writer->frame);
    if (writer->queue) av_audio_fifo_free(writer->queue);
    avcodec_free_context(&writer->encoder);
    if (writer->format) {
        if (writer->format->pb) avio_closep(&writer->format->pb);
        avformat_free_context(writer->format);
    }
    memset(writer, 0, sizeof *writer);
}

static int writer_open(OpusWriter *writer, const char *output_path, char *message, size_t size) {
    int result = avformat_alloc_output_context2(&writer->format, NULL, "ogg", NULL);
    if (result < 0) return say_av(message, size, "allocate Ogg output", result), result;
    const AVCodec *codec = avcodec_find_encoder_by_name("libopus");
    if (!codec) return say(message, size, "libopus encoder is unavailable"), AVERROR_ENCODER_NOT_FOUND;
    writer->stream = avformat_new_stream(writer->format, NULL);
    if (!writer->stream) return say(message, size, "Unable to allocate Ogg stream"), AVERROR(ENOMEM);
    writer->encoder = avcodec_alloc_context3(codec);
    if (!writer->encoder) return say(message, size, "Unable to allocate Opus encoder"), AVERROR(ENOMEM);

    AVRational time_base = av_make_q(1, OPUS_SAMPLE_RATE);
    writer->encoder->bit_rate = OPUS_BITRATE;
    writer->encoder->sample_rate = OPUS_SAMPLE_RATE;
    av_channel_layout_default(&writer->encoder->ch_layout, OPUS_CHANNELS);
    writer->encoder->sample_fmt = AV_SAMPLE_FMT_S16;
    writer->encoder->time_base = time_base;
    writer->stream->time_base = time_base;
    if (writer->format->oformat->flags & AVFMT_GLOBALHEADER) writer->encoder->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;

    AVDictionary *options = NULL;
    av_dict_set(&options, "vbr", "on", 0);
    av_dict_set(&options, "application", "audio", 0);
    result = avcodec_open2(writer->encoder, codec, &options);
    int rejected = av_dict_count(options);
    av_dict_free(&options);
    if (result < 0) return say_av(message, size, "open Opus encoder", result), result;
    if (rejected != 0) return say(message, size, "Opus encoder rejected some options"), AVERROR_OPTION_NOT_FOUND;

    result = avcodec_parameters_from_context(writer->stream->codecpar, writer->encoder);
    if (result < 0) return say_av(message, size, "copy Opus parameters", result), result;
    writer->frame_samples = writer->encoder->frame_size;
    if (writer->frame_samples <= 0) return say(message, size, "Opus encoder has no frame size"), AVERROR(EINVAL);
    writer->queue = av_audio_fifo_alloc(AV_SAMPLE_FMT_S16, OPUS_CHANNELS, writer->frame_samples * 2);
    writer->packet = av_packet_alloc();
    writer->frame = av_frame_alloc();
    if (!writer->queue || !writer->packet || !writer->frame) return say(message, size, "Unable to allocate Opus buffers"), AVERROR(ENOMEM);
    writer->frame->format = AV_SAMPLE_FMT_S16;
    writer->frame->sample_rate = OPUS_SAMPLE_RATE;
    result = av_channel_layout_copy(&writer->frame->ch_layout, &writer->encoder->ch_layout);
    if (result < 0) return say_av(message, size, "copy Opus channel layout", result), result;
    writer->frame->nb_samples = writer->frame_samples;
    result = av_frame_get_buffer(writer->frame, 0);
    if (result < 0) return say_av(message, size, "allocate Opus frame buffer", result), result;

    /* The file protocol takes UTF-8 paths on every platform. */
    result = avio_open(&writer->format->pb, output_path, AVIO_FLAG_WRITE);
    if (result < 0) return say_av(message, size, "open Ogg output file", result), result;
    result = avformat_write_header(writer->format, NULL);
    if (result < 0) return say_av(message, size, "write Ogg header", result), result;
    return 0;
}

/*
 * Reads the first audio stream of the file at input_path and writes it as Ogg Opus to
 * output_path (both UTF-8). A sound longer than max_micros microseconds gives RDI_TOO_LONG.
 * Damaged audio is tolerated as in the game: a packet that fails to decode is skipped and a
 * read error ends the sound with the frames decoded so far. On every result but RDI_OK the
 * output file may exist and is not valid; the caller removes it. message receives a short
 * English description of a failure.
 */
RDI_EXPORT int rdi_ogg_to_opus(const char *input_path, const char *output_path, int64_t max_micros, char *message, size_t message_size) {
    AVFormatContext *input = NULL;
    AVCodecContext *decoder = NULL;
    AVPacket *packet = NULL;
    AVFrame *frame = NULL;
    SwrContext *converter = NULL;
    AVChannelLayout output_layout = {0};
    uint8_t *converted = NULL;
    int converted_capacity = 0;
    OpusWriter writer = {0};
    int status = RDI_BAD_INPUT;
    int result;
    if (message && message_size) message[0] = 0;
    av_log_set_level(AV_LOG_ERROR);
    av_channel_layout_default(&output_layout, OPUS_CHANNELS);

    result = avformat_open_input(&input, input_path, NULL, NULL);
    if (result < 0) { say_av(message, message_size, "open audio input", result); goto done; }
    result = avformat_find_stream_info(input, NULL);
    if (result < 0) { say_av(message, message_size, "read audio stream information", result); goto done; }
    int stream_index = -1;
    for (unsigned index = 0; index < input->nb_streams; index++) {
        if (input->streams[index]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) { stream_index = (int)index; break; }
    }
    if (stream_index < 0) { say(message, message_size, "Input has no audio stream"); goto done; }
    AVCodecParameters *parameters = input->streams[stream_index]->codecpar;
    const AVCodec *codec = avcodec_find_decoder(parameters->codec_id);
    if (!codec) { say(message, message_size, "No decoder for audio codec %d", (int)parameters->codec_id); goto done; }
    decoder = avcodec_alloc_context3(codec);
    packet = av_packet_alloc();
    frame = av_frame_alloc();
    if (!decoder || !packet || !frame) { say(message, message_size, "Unable to allocate audio decoder"); goto done; }
    result = avcodec_parameters_to_context(decoder, parameters);
    if (result < 0) { say_av(message, message_size, "copy audio parameters", result); goto done; }
    result = avcodec_open2(decoder, codec, NULL);
    if (result < 0) { say_av(message, message_size, "open audio decoder", result); goto done; }
    if (decoder->ch_layout.nb_channels <= 0) { say(message, message_size, "OGG has no audio channels"); goto done; }

    if (writer_open(&writer, output_path, message, message_size) < 0) { status = RDI_ENCODER_FAILED; goto done; }

    int recorded = 0, flushing = 0;
    int input_format = 0, input_rate = 0, input_channels = 0;
    int64_t decoded_micros = 0;
    for (;;) {
        /* AudioFrameReader.readFrame. */
        result = avcodec_receive_frame(decoder, frame);
        if (result < 0) {
            if (result == AVERROR_EOF || flushing) break;
            int sent = 0;
            while (!sent) {
                if (av_read_frame(input, packet) < 0) break;
                if (packet->stream_index == stream_index) {
                    avcodec_send_packet(decoder, packet);
                    sent = 1;
                }
                av_packet_unref(packet);
            }
            if (!sent) {
                avcodec_send_packet(decoder, NULL);
                flushing = 1;
            }
            continue;
        }
        int rate = frame->sample_rate > 0 ? frame->sample_rate : decoder->sample_rate;
        if (rate <= 0) { say(message, message_size, "OGG has no audio sample rate"); goto done; }
        int64_t frame_micros = ((int64_t)frame->nb_samples * 1000000 + rate - 1) / rate;
        if (frame_micros > max_micros - decoded_micros) { status = RDI_TOO_LONG; goto done; }
        decoded_micros += frame_micros;

        /* Pcm16Converter.convert. */
        if (!converter) {
            result = swr_alloc_set_opts2(&converter, &output_layout, AV_SAMPLE_FMT_S16, OPUS_SAMPLE_RATE,
                                         &frame->ch_layout, frame->format, frame->sample_rate, 0, NULL);
            if (result < 0) { say_av(message, message_size, "configure audio converter", result); goto done; }
            result = swr_init(converter);
            if (result < 0) { say_av(message, message_size, "initialize audio converter", result); goto done; }
            input_format = frame->format;
            input_rate = frame->sample_rate;
            input_channels = frame->ch_layout.nb_channels;
        } else if (frame->format != input_format || frame->sample_rate != input_rate || frame->ch_layout.nb_channels != input_channels) {
            /* The planes of a frame in another format would be read out of bounds. */
            say(message, message_size, "Audio format changed in the middle of the stream");
            goto done;
        }
        int capacity = swr_get_out_samples(converter, frame->nb_samples);
        if (capacity < 0) { say_av(message, message_size, "size audio output", capacity); goto done; }
        if (capacity > converted_capacity) {
            av_free(converted);
            converted = av_malloc((size_t)capacity * OPUS_CHANNELS * sizeof(int16_t));
            if (!converted) { say(message, message_size, "Unable to allocate audio output"); goto done; }
            converted_capacity = capacity;
        }
        int count = swr_convert(converter, &converted, capacity, (const uint8_t *const *)frame->extended_data, frame->nb_samples);
        if (count < 0) { say_av(message, message_size, "convert audio samples", count); goto done; }
        result = writer_write(&writer, &converted, count);
        if (result < 0) { say_av(message, message_size, "write Opus audio", result); status = RDI_ENCODER_FAILED; goto done; }
        recorded = 1;
    }
    if (!recorded) { say(message, message_size, "OGG contains no decodable audio frames"); goto done; }

    /* Pcm16Converter.flush. */
    int remaining = 0;
    if (converter) {
        int capacity = swr_get_out_samples(converter, 0);
        if (capacity < 0) { say_av(message, message_size, "size audio output", capacity); goto done; }
        if (capacity > converted_capacity) {
            av_free(converted);
            converted = av_malloc((size_t)capacity * OPUS_CHANNELS * sizeof(int16_t));
            if (!converted) { say(message, message_size, "Unable to allocate audio output"); goto done; }
            converted_capacity = capacity;
        }
        if (capacity > 0) {
            remaining = swr_convert(converter, &converted, capacity, NULL, 0);
            if (remaining < 0) { say_av(message, message_size, "flush audio samples", remaining); goto done; }
        }
    }
    result = writer_write(&writer, &converted, remaining);
    if (result >= 0) result = writer_finish(&writer);
    if (result < 0) { say_av(message, message_size, "finish Opus audio", result); status = RDI_ENCODER_FAILED; goto done; }
    status = RDI_OK;

done:
    writer_close(&writer);
    swr_free(&converter);
    av_free(converted);
    av_channel_layout_uninit(&output_layout);
    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&decoder);
    avformat_close_input(&input);
    return status;
}

/* ---- Picture: RGBA to a still AVIF, 10 bit, with a lossless alpha image when needed ---- */

typedef struct AvifEncoder {
    AVCodecContext *context;
    AVStream *stream;
} AvifEncoder;

static int avif_option(AVCodecContext *context, const char *name, const char *value) {
    return av_opt_set(context, name, value, AV_OPT_SEARCH_CHILDREN);
}

static int avif_create(AvifEncoder *encoder, AVFormatContext *format, int width, int height, enum AVPixelFormat pixel_format, int crf, int threads,
                       char *message, size_t size) {
    const AVCodec *codec = avcodec_find_encoder_by_name("libaom-av1");
    if (!codec) return say(message, size, "FFmpeg libaom-av1 encoder is unavailable"), AVERROR_ENCODER_NOT_FOUND;
    AVCodecContext *context = avcodec_alloc_context3(codec);
    if (!context) return say(message, size, "Unable to allocate libaom-av1 encoder context"), AVERROR(ENOMEM);
    encoder->context = context;
    AVRational time_base = {1, 1};
    char crf_text[16];
    snprintf(crf_text, sizeof crf_text, "%d", crf);
    context->width = width;
    context->height = height;
    context->pix_fmt = pixel_format;
    context->time_base = time_base;
    context->framerate = time_base;
    context->gop_size = 1;
    context->thread_count = threads < 1 ? 1 : threads > 4 ? 4 : threads;
    context->color_range = AVCOL_RANGE_JPEG;
    context->color_primaries = AVCOL_PRI_BT709;
    context->color_trc = AVCOL_TRC_IEC61966_2_1;
    context->colorspace = AVCOL_SPC_BT709;
    int result;
    if ((result = avif_option(context, "crf", crf_text)) < 0 || (result = avif_option(context, "cpu-used", "6")) < 0 ||
        (result = avif_option(context, "still-picture", "1")) < 0 || (result = avif_option(context, "usage", "allintra")) < 0 ||
        (result = avif_option(context, "row-mt", "1")) < 0) {
        return say_av(message, size, "set libaom option", result), result;
    }
    if (format->oformat->flags & AVFMT_GLOBALHEADER) context->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    result = avcodec_open2(context, codec, NULL);
    if (result < 0) return say_av(message, size, "open libaom-av1 encoder", result), result;
    encoder->stream = avformat_new_stream(format, codec);
    if (!encoder->stream) return say(message, size, "Unable to allocate AVIF video stream"), AVERROR(ENOMEM);
    encoder->stream->time_base = time_base;
    result = avcodec_parameters_from_context(encoder->stream->codecpar, context);
    if (result < 0) return say_av(message, size, "copy AV1 encoder parameters", result), result;
    return 0;
}

static int avif_drain(AvifEncoder *encoder, AVFormatContext *format) {
    AVPacket *packet = av_packet_alloc();
    if (!packet) return AVERROR(ENOMEM);
    int result = 0;
    for (;;) {
        result = avcodec_receive_packet(encoder->context, packet);
        if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) { result = 0; break; }
        if (result < 0) break;
        av_packet_rescale_ts(packet, encoder->context->time_base, encoder->stream->time_base);
        packet->stream_index = encoder->stream->index;
        result = av_interleaved_write_frame(format, packet);
        if (result < 0) break;
    }
    av_packet_free(&packet);
    return result;
}

/* One frame in, then the end of the stream, as encodeFrame and flushEncoder. */
static int avif_encode(AvifEncoder *encoder, AVFrame *frame, AVFormatContext *format) {
    int result = avcodec_send_frame(encoder->context, frame);
    if (result >= 0) result = avif_drain(encoder, format);
    if (result >= 0) result = avcodec_send_frame(encoder->context, NULL);
    if (result >= 0) result = avif_drain(encoder, format);
    return result;
}

static AVFrame *avif_frame(enum AVPixelFormat pixel_format, int width, int height) {
    AVFrame *frame = av_frame_alloc();
    if (!frame) return NULL;
    frame->format = pixel_format;
    frame->width = width;
    frame->height = height;
    if (av_frame_get_buffer(frame, 32) < 0) av_frame_free(&frame);
    return frame;
}

/*
 * Encodes width x height pixels of 8-bit RGBA (not premultiplied, rows without padding) as a
 * still AVIF: 10-bit colour at CRF 30, 4:4:4 when high_quality is not 0 and 4:2:0 otherwise, and
 * a lossless 10-bit alpha image when any pixel is not opaque. threads is the caller's processor
 * count; 1 to 4 are used. On RDI_OK *output holds *output_size bytes to release with
 * rdi_media_free. A failure is RDI_ENCODER_FAILED; the picture is then kept as it was.
 */
RDI_EXPORT int rdi_rgba_to_avif(const uint8_t *rgba, int width, int height, int high_quality, int threads, uint8_t **output, size_t *output_size,
                                char *message, size_t message_size) {
    AVFormatContext *format = NULL;
    AvifEncoder color = {0}, alpha = {0};
    AVFrame *source = NULL, *converted = NULL, *alpha_frame = NULL;
    struct SwsContext *scaler = NULL;
    int status = RDI_ENCODER_FAILED;
    int result;
    if (message && message_size) message[0] = 0;
    if (!rgba || !output || !output_size || width <= 0 || height <= 0) { say(message, message_size, "Invalid picture"); return status; }
    *output = NULL;
    *output_size = 0;
    av_log_set_level(AV_LOG_ERROR);
    enum AVPixelFormat color_format = high_quality ? AV_PIX_FMT_YUV444P10LE : AV_PIX_FMT_YUV420P10LE;
    int has_alpha = 0;
    for (size_t index = 3, end = (size_t)width * (size_t)height * 4; index < end; index += 4) {
        if (rgba[index] != 0xFF) { has_alpha = 1; break; }
    }

    result = avformat_alloc_output_context2(&format, NULL, "avif", NULL);
    if (result < 0) { say_av(message, message_size, "allocate AVIF output context", result); goto done; }
    result = avio_open_dyn_buf(&format->pb);
    if (result < 0) { say_av(message, message_size, "open AVIF memory output", result); goto done; }
    if (avif_create(&color, format, width, height, color_format, AVIF_COLOR_CRF, threads, message, message_size) < 0) goto done;
    if (has_alpha && avif_create(&alpha, format, width, height, AV_PIX_FMT_GRAY10LE, AVIF_ALPHA_CRF, threads, message, message_size) < 0) goto done;
    result = avformat_write_header(format, NULL);
    if (result < 0) { say_av(message, message_size, "write AVIF header", result); goto done; }

    source = avif_frame(AV_PIX_FMT_RGBA, width, height);
    converted = avif_frame(color_format, width, height);
    if (!source || !converted) { say(message, message_size, "Unable to allocate FFmpeg frame"); goto done; }
    for (int row = 0; row < height; row++) {
        memcpy(source->data[0] + (size_t)row * source->linesize[0], rgba + (size_t)row * width * 4, (size_t)width * 4);
    }
    scaler = sws_getContext(width, height, AV_PIX_FMT_RGBA, width, height, color_format, SWS_BILINEAR, NULL, NULL, NULL);
    if (!scaler) { say(message, message_size, "Unable to allocate RGBA to 10-bit YUV converter"); goto done; }
    if (sws_scale(scaler, (const uint8_t *const *)source->data, source->linesize, 0, height, converted->data, converted->linesize) != height) {
        say(message, message_size, "FFmpeg failed to convert RGBA to 10-bit YUV");
        goto done;
    }
    converted->pts = 0;
    result = avif_encode(&color, converted, format);
    if (result < 0) { say_av(message, message_size, "encode AVIF colour", result); goto done; }

    if (has_alpha) {
        alpha_frame = avif_frame(AV_PIX_FMT_GRAY10LE, width, height);
        if (!alpha_frame) { say(message, message_size, "Unable to allocate FFmpeg frame"); goto done; }
        for (int y = 0; y < height; y++) {
            uint8_t *row = alpha_frame->data[0] + (size_t)y * alpha_frame->linesize[0];
            for (int x = 0; x < width; x++) {
                int alpha8 = rgba[((size_t)y * width + x) * 4 + 3];
                int alpha10 = (alpha8 * 1023 + 127) / 255;
                row[x * 2] = (uint8_t)(alpha10 & 0xFF);
                row[x * 2 + 1] = (uint8_t)(alpha10 >> 8);
            }
        }
        alpha_frame->pts = 0;
        result = avif_encode(&alpha, alpha_frame, format);
        if (result < 0) { say_av(message, message_size, "encode AVIF alpha", result); goto done; }
    }
    result = av_write_trailer(format);
    if (result < 0) { say_av(message, message_size, "write AVIF trailer", result); goto done; }
    uint8_t *buffer = NULL;
    int size = avio_close_dyn_buf(format->pb, &buffer);
    format->pb = NULL;
    if (size <= 0 || !buffer) {
        av_free(buffer);
        say(message, message_size, "FFmpeg produced an empty AVIF image");
        goto done;
    }
    *output = buffer;
    *output_size = (size_t)size;
    status = RDI_OK;

done:
    if (format && format->pb) {
        uint8_t *discarded = NULL;
        avio_close_dyn_buf(format->pb, &discarded);
        av_free(discarded);
        format->pb = NULL;
    }
    sws_freeContext(scaler);
    av_frame_free(&alpha_frame);
    av_frame_free(&converted);
    av_frame_free(&source);
    avcodec_free_context(&color.context);
    avcodec_free_context(&alpha.context);
    if (format) avformat_free_context(format);
    return status;
}
