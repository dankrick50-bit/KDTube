package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SoundEnhanceProcessor;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.misc.AudioOutputInfo;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

import java.util.ArrayList;
import java.util.List;

/**
 * "Picture &amp; sound" of the player: sharpening, volume levelling, dialogue boost, wide stereo, virtual surround.<br/>
 * The options live in the quality dialog (the HQ button). Everything is off until switched on.
 */
public class PictureSoundController extends BasePlayerController {
    private static final int CATEGORY_ID = 7_350;

    @Override
    public void onInit() {
        applySound();
        addCategory();
    }

    @Override
    public void onViewResumed() {
        addCategory();
    }

    @Override
    public void onVideoLoaded(Video item) {
        // NOTE: the format (HDR or not) is known at this point
        applySharpen();
    }

    private void addCategory() {
        HQDialogController dialogController = getController(HQDialogController.class);

        if (dialogController == null || getContext() == null) {
            return;
        }

        List<OptionItem> options = new ArrayList<>();
        options.add(UiOptionItem.from(getContext().getString(R.string.sharpen), optionItem -> showSharpenDialog()));
        options.add(UiOptionItem.from(getContext().getString(R.string.volume_levelling), optionItem -> showLevellingDialog()));
        options.add(UiOptionItem.from(getContext().getString(R.string.bass_boost), optionItem -> showBassDialog()));
        options.add(UiOptionItem.from(getContext().getString(R.string.virtual_surround), optionItem -> showSurroundDialog()));
        options.add(UiOptionItem.from(getContext().getString(R.string.sound_options), optionItem -> showSoundDialog()));

        dialogController.addCategory(OptionCategory.from(
                CATEGORY_ID, OptionCategory.TYPE_STRING_LIST, getContext().getString(R.string.picture_and_sound), options));
    }

    private void showSharpenDialog() {
        PlayerTweaksData data = getPlayerTweaksData();
        List<OptionItem> options = new ArrayList<>();

        for (int[] pair : new int[][] {
                {R.string.option_off, PlayerTweaksData.SHARPEN_OFF},
                {R.string.sharpen_light, PlayerTweaksData.SHARPEN_LIGHT},
                {R.string.sharpen_strong, PlayerTweaksData.SHARPEN_STRONG}}) {
            options.add(UiOptionItem.from(getContext().getString(pair[0]), optionItem -> {
                data.setSharpenLevel(pair[1]);
                applySharpen();
            }, data.getSharpenLevel() == pair[1]));
        }

        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.appendRadioCategory(getContext().getString(R.string.sharpen), options);
        dialog.showDialog(getContext().getString(R.string.sharpen));
    }

    private void showLevellingDialog() {
        PlayerTweaksData data = getPlayerTweaksData();
        List<OptionItem> options = new ArrayList<>();

        for (int[] pair : new int[][] {
                {R.string.option_off, SoundEnhanceProcessor.LEVELLING_OFF},
                {R.string.option_on, SoundEnhanceProcessor.LEVELLING_ON},
                {R.string.levelling_night, SoundEnhanceProcessor.LEVELLING_NIGHT}}) {
            options.add(UiOptionItem.from(getContext().getString(pair[0]), optionItem -> {
                data.setSoundLevelling(pair[1]);
                applySound();
            }, data.getSoundLevelling() == pair[1]));
        }

        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.appendRadioCategory(getContext().getString(R.string.volume_levelling), options);
        dialog.showDialog(getContext().getString(R.string.volume_levelling));
    }

    private void showBassDialog() {
        PlayerTweaksData data = getPlayerTweaksData();
        List<OptionItem> options = new ArrayList<>();

        for (int[] pair : new int[][] {
                {R.string.option_off, SoundEnhanceProcessor.BASS_OFF},
                {R.string.bass_light, SoundEnhanceProcessor.BASS_LIGHT},
                {R.string.bass_strong, SoundEnhanceProcessor.BASS_STRONG}}) {
            options.add(UiOptionItem.from(getContext().getString(pair[0]), optionItem -> {
                data.setBassBoost(pair[1]);
                applySound();
            }, data.getBassBoost() == pair[1]));
        }

        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.appendRadioCategory(getContext().getString(R.string.bass_boost), options);
        dialog.showDialog(getContext().getString(R.string.bass_boost));
    }

    private void showSoundDialog() {
        PlayerTweaksData data = getPlayerTweaksData();
        List<OptionItem> options = new ArrayList<>();

        options.add(UiOptionItem.from(getContext().getString(R.string.dialogue_boost), getContext().getString(R.string.dialogue_boost_desc), optionItem -> {
            data.setDialogueBoostEnabled(optionItem.isSelected());
            applySound();
        }, data.isDialogueBoostEnabled()));

        options.add(UiOptionItem.from(getContext().getString(R.string.wide_stereo), getContext().getString(R.string.wide_stereo_desc), optionItem -> {
            data.setWideStereoEnabled(optionItem.isSelected());
            applySound();
        }, data.isWideStereoEnabled()));

        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.appendCheckedCategory(getContext().getString(R.string.sound_options), options);
        dialog.showDialog(getContext().getString(R.string.sound_options));
    }

    private void showSurroundDialog() {
        PlayerTweaksData data = getPlayerTweaksData();
        List<OptionItem> options = new ArrayList<>();

        for (int[] pair : new int[][] {
                {R.string.virtual_surround_auto, PlayerTweaksData.VIRTUAL_SURROUND_AUTO},
                {R.string.option_on, PlayerTweaksData.VIRTUAL_SURROUND_ON},
                {R.string.option_off, PlayerTweaksData.VIRTUAL_SURROUND_OFF}}) {
            options.add(UiOptionItem.from(getContext().getString(pair[0]), optionItem -> {
                data.setVirtualSurroundMode(pair[1]);
                applySound();

                // The output may have another number of channels now: build it again
                if (getPlayer() != null) {
                    getPlayer().restartEngine();
                }
            }, data.getVirtualSurroundMode() == pair[1]));
        }

        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        dialog.appendRadioCategory(getContext().getString(R.string.virtual_surround), options);
        dialog.showDialog(getContext().getString(R.string.virtual_surround));
    }

    /**
     * 5.1 / 7.1 sound on two channels: the app does the mix when asked to, or by itself when nothing
     * behind it has more than two speakers.
     */
    private boolean isVirtualSurroundNeeded() {
        int mode = getPlayerTweaksData().getVirtualSurroundMode();

        return mode == PlayerTweaksData.VIRTUAL_SURROUND_ON ||
                (mode == PlayerTweaksData.VIRTUAL_SURROUND_AUTO && !AudioOutputInfo.isSurroundOutput(getContext()));
    }

    private void applySound() {
        PlayerTweaksData data = getPlayerTweaksData();
        SoundEnhanceProcessor.setOptions(
                data.getSoundLevelling(), data.isDialogueBoostEnabled(), data.isWideStereoEnabled(), isVirtualSurroundNeeded(),
                data.getBassBoost());
    }

    private void applySharpen() {
        if (getPlayer() == null) {
            return;
        }

        int level = getPlayerTweaksData().getSharpenLevel();

        // The sharpening goes through the GPU: no HDR there, and the tunneled mode draws by itself
        if (level != PlayerTweaksData.SHARPEN_OFF && (getPlayerTweaksData().isTunneledPlaybackEnabled() || isHdr())) {
            level = PlayerTweaksData.SHARPEN_OFF;
        }

        getPlayer().setSharpenLevel(level);
    }

    private boolean isHdr() {
        FormatItem format = getPlayer().getVideoFormat();

        return format != null && format.getTitle() != null && Helpers.contains(format.getTitle().toString(), "HDR");
    }
}
