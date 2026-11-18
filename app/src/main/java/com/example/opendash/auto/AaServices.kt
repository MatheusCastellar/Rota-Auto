package com.example.opendash.auto

import com.andrerinas.openheadunit.aap.protocol.proto.*

object AaServices {
    private const val VEHICLE_ID="ROTA-AUTO-LOCAL-MOTO-V3"
    fun discovery(microphone:Boolean=false,phoneAudio:Boolean=false):Control.ServiceDiscoveryResponse {
        val d=Control.ServiceDiscoveryResponse.newBuilder().setMake("Royal Enfield").setModel("Tripper Dash")
            .setYear("2026").setVehicleId(VEHICLE_ID).setDriverPosition(Control.DriverPosition.DRIVER_POSITION_CENTER)
            .setHeadUnitMake("Rota Auto independente").setHeadUnitModel("Tripper bridge").setHeadUnitSoftwareBuild("11")
            .setHeadUnitSoftwareVersion("0.1.10").setCanPlayNativeMediaDuringVr(false).setDisplayName("Rota Auto").setHideProjectedClock(false)
            .setSessionConfiguration(0)
            // A receiver without a microphone must identify the actual motorcycle explicitly.
            // Otherwise AA assumes a car and may abort setup with "No audio/mic".
            // Use a new id because AA caches vehicle type by head-unit identity.
            .setHeadunitInfo(Common.HeadUnitInfo.newBuilder().setMake("Royal Enfield")
                .setModel("Tripper Dash").setYear("2026").setVehicleId(VEHICLE_ID)
                .setHeadUnitMake("Rota Auto independente").setHeadUnitModel("Tripper bridge")
                .setHeadUnitSoftwareBuild("11").setHeadUnitSoftwareVersion("0.1.10").setVehicleType(3))
        val sensors=Control.Service.SensorSourceService.newBuilder()
        AaSensors.supported.forEach { sensors.addSensors(Control.Service.SensorSourceService.Sensor.newBuilder().setType(it)) }
        d.addServices(Control.Service.newBuilder().setId(1).setSensorSourceService(sensors))
        val v=Control.Service.MediaSinkService.VideoConfiguration.newBuilder()
            .setCodecResolution(Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480)
            .setFrameRate(Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30)
            .setMarginWidth(0).setMarginHeight(0)
            .setDensity(160).setPixelAspectRatioE4(10000).setDecoderAdditionalDepth(0)
            .setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
        d.addServices(Control.Service.newBuilder().setId(2).setMediaSinkService(Control.Service.MediaSinkService.newBuilder()
            .setAvailableType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP).setAvailableWhileInCall(true).addVideoConfigs(v)))
        d.addServices(Control.Service.newBuilder().setId(3).setInputSourceService(Control.Service.InputSourceService.newBuilder()
            .setTouchscreen(Control.Service.InputSourceService.TouchConfig.newBuilder().setWidth(800).setHeight(480))
            .addAllKeycodesSupported(listOf(3,4,19,20,21,22,23,66,84,85,87,88,127,187,65536,65537,65538))))
        // Keep music on the phone's normal output. Advertising MEDIA would divert
        // Spotify into a PCM round trip through this same-phone receiver.
        for(id in 4..5) {
            // SYSTEM remains advertised: some AA versions otherwise stop video setup.
            // Omitting SPEECH lets the phone own navigation/assistant playback.
            if(id==4 && phoneAudio) continue
            val media=id==6
            d.addServices(Control.Service.newBuilder().setId(id).setMediaSinkService(Control.Service.MediaSinkService.newBuilder()
                .setAvailableType(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
                .setAudioType(if(media) Media.AudioStreamType.MEDIA else if(id==4) Media.AudioStreamType.SPEECH else Media.AudioStreamType.SYSTEM)
                .setAvailableWhileInCall(true).addAudioConfigs(Media.AudioConfiguration.newBuilder()
                    .setSampleRate(if(media) 48000 else 16000).setNumberOfBits(16).setNumberOfChannels(if(media) 2 else 1))))
        }
        if(microphone) d.addServices(Control.Service.newBuilder().setId(7).setMediaSourceService(Control.Service.MediaSourceService.newBuilder()
            .setType(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM).setAvailableWhileInCall(false)
            .setAudioConfig(Media.AudioConfiguration.newBuilder().setSampleRate(16000).setNumberOfBits(16).setNumberOfChannels(1))))
        d.addServices(Control.Service.newBuilder().setId(10).setMediaPlaybackService(Control.Service.MediaPlaybackStatusService.getDefaultInstance()))
        d.addServices(Control.Service.newBuilder().setId(11).setNavigationStatusService(
            Control.Service.NavigationStatusService.newBuilder().setMinimumIntervalMs(1000)
                .setType(Control.Service.NavigationStatusService.ClusterType.ImageCodesOnly)))
        return d.build()
    }
}
