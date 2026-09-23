package com.forest.offgrid.ui.landing

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.forest.offgrid.R
import com.forest.offgrid.databinding.FragmentLandingBinding

class LandingFragment : Fragment() {

    private var _binding: FragmentLandingBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater, 
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.d("LandingFragment", "onCreateView started")
        
        _binding = FragmentLandingBinding.inflate(inflater, container, false)
        
        Log.d("LandingFragment", "Binding inflated, setting up views")
        
        // Enable animations
        setupAnimations()
        setupListeners()
        
        Log.d("LandingFragment", "onCreateView completed, returning root view")
        
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        view.post {
            Log.d("LandingFragment", "View dimensions: ${view.width} x ${view.height}")
            Log.d("LandingFragment", "Root alpha: ${binding.root.alpha}")
            Log.d("LandingFragment", "Title visibility: ${binding.textAppName.visibility}, alpha: ${binding.textAppName.alpha}")
        }
    }

    private fun setupAnimations() {
        // Start animations after a short delay for smooth entry
        handler.postDelayed({
            animateLogo()
            animateTitle()
            animateTagline()
            animateMission()
            animateFeatures()
            animateButtons()
        }, 100)
    }

    private fun animateLogo() {
        // Pulsing glow effect for logo
        val pulseOuter = ObjectAnimator.ofPropertyValuesHolder(
            binding.logoGlowOuter,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.2f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.2f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.2f, 0.0f)
        ).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }

        val pulseInner = ObjectAnimator.ofPropertyValuesHolder(
            binding.logoGlowInner,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.15f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.15f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.3f, 0.0f)
        ).apply {
            duration = 2000
            startDelay = 300
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }

        // Logo entrance animation
        binding.logoContainer.alpha = 0f
        binding.logoContainer.scaleX = 0.5f
        binding.logoContainer.scaleY = 0.5f
        
        ObjectAnimator.ofPropertyValuesHolder(
            binding.logoContainer,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.5f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.5f, 1f)
        ).apply {
            duration = 800
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun animateTitle() {
        binding.textAppName.alpha = 0f
        binding.textAppName.translationY = 100f
        
        binding.textAppName.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setStartDelay(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateTagline() {
        binding.textTagline.alpha = 0f
        binding.textTagline.translationY = 80f
        
        binding.textTagline.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setStartDelay(400)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateMission() {
        binding.textMission.alpha = 0f
        binding.textMission.translationY = 60f
        
        binding.textMission.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setStartDelay(600)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateFeatures() {
        binding.featureIndicators.alpha = 0f
        binding.featureIndicators.translationY = 40f
        
        binding.featureIndicators.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setStartDelay(800)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateButtons() {
        // Primary button
        binding.btnEnterCommandCenter.alpha = 0f
        binding.btnEnterCommandCenter.translationY = 50f
        
        binding.btnEnterCommandCenter.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setStartDelay(1000)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // Secondary button
        binding.btnConnectDevice.alpha = 0f
        binding.btnConnectDevice.translationY = 50f
        
        binding.btnConnectDevice.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(800)
            .setStartDelay(1100)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun setupListeners() {
        // Primary CTA - Enter Command Center
        binding.btnEnterCommandCenter.setOnClickListener {
            animateButtonPress(it) {
                navigateToDashboard()
            }
        }

        // Secondary CTA - Connect Device (navigate to devices)
        binding.btnConnectDevice.setOnClickListener {
            animateButtonPress(it) {
                // Navigate to devices screen
                findNavController().navigate(R.id.action_landing_to_devices)
            }
        }
    }

    private fun animateButtonPress(view: View, onComplete: () -> Unit) {
        // Scale down
        view.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(100)
            .withEndAction {
                // Scale back up
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .withEndAction(onComplete)
                    .start()
            }
            .start()
    }

    private fun navigateToDashboard() {
        findNavController().navigate(R.id.action_landing_to_dashboard)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        _binding = null
    }
}
