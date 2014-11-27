package org.lazywizard.memwarn;

import java.awt.Color;
import java.awt.Desktop;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URISyntaxException;
import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import org.apache.log4j.Level;
import org.json.JSONObject;
import org.lwjgl.input.Keyboard;

/**
 * @author LazyWizard
 */
public class MWModPlugin extends BaseModPlugin
{
    private static final String SETTINGS_FILE = "mem_settings.json";
    private static int INSTRUCTIONS_THREAD, OPEN_THREAD_KEY, RECOMMENDED_MEMORY_MB;
    private static boolean hasChecked = false;

    @Override
    public void onApplicationLoad() throws Exception
    {
        // Load mod settings from JSON
        final JSONObject settings = Global.getSettings().loadJSON(SETTINGS_FILE);
        RECOMMENDED_MEMORY_MB = settings.optInt("recommendedMaxMemoryInMegabytes", 2048);
        INSTRUCTIONS_THREAD = settings.optInt("instructionsThreadId", 0);
        OPEN_THREAD_KEY = settings.optInt("launchInstructionsThreadKey", 41);
    }

    private static boolean hasEnoughMemoryAllocated()
    {
        // Support for players who replaced their JRE with a non-HotSpot one
        // It's possible that the other way works for these VMs; haven't checked
        // IMPORTANT: The -Xmx flag is a _suggestion_ and the actual max memory used
        // by the JRE will be noticably lower - often hundreds of megabytes less!
        // That's why we're only checking for 80% of recommended max memory here
        final String vmName = System.getProperty("java.vm.name");
        if (vmName == null || !vmName.toLowerCase().contains("hotspot"))
        {
            Global.getLogger(MWModPlugin.class).log(Level.DEBUG,
                    "Found non-HotSpot VM: " + vmName);
            final int max = (int) (Runtime.getRuntime().maxMemory() / 1048576l);
            final int recommended = (int) (RECOMMENDED_MEMORY_MB * .8f);
            Global.getLogger(MWModPlugin.class).log(Level.INFO,
                    "Memory: " + max + "mb allocatable, " + recommended + "mb recommended");
            return (max >= recommended);
        }

        // Standard HotSpot VM? Check command line arguments directly
        Global.getLogger(MWModPlugin.class).log(Level.DEBUG,
                "Found HotSpot VM: " + vmName);
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments())
        {
            arg = arg.toLowerCase().trim();
            if (!arg.startsWith("-xmx"))
            {
                continue;
            }

            // Found the -Xmx flag, parse the arguments passed in
            int max = Integer.parseInt(arg.substring(4, arg.length() - 1));
            final char byteType = arg.charAt(arg.length() - 1);

            // Convert allocated memory to megabytes
            switch (byteType)
            {
                case 'k':
                    max /= 1024;
                    break;
                case 'g':
                    max *= 1024;
                    break;
                default:
            }

            Global.getLogger(MWModPlugin.class).log(Level.INFO,
                    "Memory: " + max + "mb allocatable, " + RECOMMENDED_MEMORY_MB + "mb recommended");
            return (max >= RECOMMENDED_MEMORY_MB);
        }

        // Default to warning about memory just to be safe
        Global.getLogger(MWModPlugin.class).log(Level.WARN,
                "-Xmx arg not found in command line options!");
        return false;
    }

    @Override
    public void onGameLoad()
    {
        // Only warn once per play session
        if (hasChecked)
        {
            return;
        }

        hasChecked = true;
        if (!hasEnoughMemoryAllocated())
        {
            Global.getSector().addTransientScript(new MWNotificationScript());
        }
    }

    private class MWNotificationScript implements EveryFrameScript
    {
        private float timeUntilWarn = 1f;
        private boolean isDone = false, hasWarned = false;

        @Override
        public boolean isDone()
        {
            return isDone;
        }

        @Override
        public boolean runWhilePaused()
        {
            return true;
        }

        @Override
        public void advance(float amount)
        {
            // Don't show warning when the player won't be able to see it
            final CampaignUIAPI ui = Global.getSector().getCampaignUI();
            if (isDone || ui.isShowingDialog() || Global.getSector().isInNewGameAdvance())
            {
                return;
            }

            // Only warn about memory allocation once
            if (!hasWarned)
            {
                // Give enough time for the UI to settle, otherwise the
                // message will sometimes never appear on a game load
                timeUntilWarn -= amount;
                if (timeUntilWarn <= 0f)
                {
                    hasWarned = true;
                    ui.addMessage("You may not have enough memory allocated to run Starsector+"
                            + " without crashes.", Color.YELLOW);

                    // If there's a valid instructions thread, wait for keypress
                    if (INSTRUCTIONS_THREAD != 0)
                    {
                        ui.addMessage("If you wish to allocate more memory, press "
                                + Keyboard.getKeyName(OPEN_THREAD_KEY)
                                + " to go to a forum post with instructions.",
                                Color.YELLOW, Keyboard.getKeyName(OPEN_THREAD_KEY), Color.CYAN);
                    }
                    // If no thread was found, remove script immediately
                    else
                    {
                        isDone = true;
                    }
                }
            }
            // If the player presses this key, go to the forum post containing
            // instructions for allocating more memory to a 64-bit JRE, then
            // remove this script the next frame
            else if (Keyboard.isKeyDown(OPEN_THREAD_KEY))
            {
                isDone = true;
                try
                {
                    Desktop.getDesktop().browse(new URI(String.format(
                            "http://fractalsoftworks.com/forum/index.php?topic=%d.0",
                            INSTRUCTIONS_THREAD)));
                }
                catch (IOException | URISyntaxException ex)
                {
                    ui.addMessage("Failed to launch web browser: " + ex.getMessage(),
                            Color.RED);
                    Global.getLogger(MWNotificationScript.class).log(Level.ERROR, ex);
                }
            }
        }
    }
}
