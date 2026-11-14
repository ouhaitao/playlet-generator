package org.playlet.media;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** FfmpegRunner 进度解析离线测试（不触真实 ffmpeg）。 */
class FfmpegRunnerTest {

    @Test
    void parsePercent_fromTimeLine() {
        String line = "frame=  120 fps= 30 q=28.0 size=    1024kB time=00:00:05.00 bitrate= 100kbits/s speed=2x";
        assertThat(FfmpegRunner.parsePercent(line, 10.0)).isCloseTo(50.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void parsePercent_fractionalSeconds() {
        String line = "time=00:00:07.500";
        assertThat(FfmpegRunner.parsePercent(line, 15.0)).isCloseTo(50.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void parsePercent_noTime_returnsNegative() {
        assertThat(FfmpegRunner.parsePercent("frame= 10 fps=30", 10.0)).isEqualTo(-1.0);
    }

    @Test
    void parsePercent_zeroDuration_returnsNegative() {
        assertThat(FfmpegRunner.parsePercent("time=00:00:01.00", 0)).isEqualTo(-1.0);
    }

    @Test
    void parsePercent_hoursAndMinutes() {
        String line = "time=00:01:30.00"; // 90s
        assertThat(FfmpegRunner.parsePercent(line, 180.0)).isCloseTo(50.0, org.assertj.core.data.Offset.offset(0.01));
    }
}
