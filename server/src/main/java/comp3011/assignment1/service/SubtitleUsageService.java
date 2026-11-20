package comp3011.assignment1.service;

import org.springframework.stereotype.Service;

/**
 * Tracks cumulative Whisper transcription duration in seconds.
 *
 * Synchronized methods protect the shared total from concurrent
 * Spring request threads.
 *
 * Author: Raúl Ruiz Sánchez, a3229619
 * Developed with assistance from ChatGPT.
 */
@Service
public class SubtitleUsageService {

    private double seconds;

    public synchronized void addSeconds(double amount) {
        if (Double.isFinite(amount) && amount > 0) {
            seconds += amount;
        }
    }

    public synchronized double getSeconds() {
        return seconds;
    }
}
