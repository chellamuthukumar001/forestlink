#include "route_scorer_model.h"

#define LORA_SF7_SNR_LIMIT -7.5f

void route_scorer_extract_features(const ForestLinkTelemetryPacket *pkt, float *f) {
    float rssi = (float)pkt->rssi;
    float snr = fl_unpack_snr(pkt->snr_x4);
    float link_margin = snr - LORA_SF7_SNR_LIMIT;
    float packet_loss = (float)pkt->packet_loss_rate;
    float battery_pct = (float)pkt->battery_pct;
    float battery_mv = (float)fl_unpack_battery_mv(pkt->battery_mv_scaled);
    float queue_len = (float)pkt->queue_len;
    float congestion_ratio = queue_len / 32.0f;
    if (congestion_ratio > 2.0f) congestion_ratio = 2.0f;
    float distance_m = (float)pkt->distance_m;
    float hop_count = (float)fl_get_hop_count(pkt->hop_and_flags);
    float last_seen = (float)pkt->last_seen_sec;
    float freshness = 1.0f / (1.0f + (last_seen / 60.0f));

    // Array order strictly matches FEATURE_NAMES:
    f[0]  = rssi;
    f[1]  = snr;
    f[2]  = link_margin;
    f[3]  = packet_loss;
    f[4]  = battery_pct;
    f[5]  = battery_mv;
    f[6]  = queue_len;
    f[7]  = congestion_ratio;
    f[8]  = distance_m;
    f[9]  = hop_count;
    f[10] = last_seen;
    f[11] = freshness;
}

// ================= Transpiled Decision Trees =================
// Tree 0 (Max Depth 4)
static inline float eval_tree_0(const float *f) {
    // f[2] = link_margin
    if (f[2] <= 1.470000f) {
        // f[4] = battery_pct
        if (f[4] <= 61.500000f) {
            // f[2] = link_margin
            if (f[2] <= 0.395000f) {
                // f[10] = last_seen_sec
                if (f[10] <= 279.000000f) {
                    return 0.000000f;
                } else {
                    return 0.083333f;
                }
            } else {
                // f[11] = freshness_factor
                if (f[11] <= 0.827626f) {
                    return 0.000000f;
                } else {
                    return 0.562500f;
                }
            }
        } else {
            // f[2] = link_margin
            if (f[2] <= 0.090000f) {
                // f[10] = last_seen_sec
                if (f[10] <= 2.500000f) {
                    return 0.018182f;
                } else {
                    return 0.000000f;
                }
            } else {
                // f[8] = distance_m
                if (f[8] <= 502.500000f) {
                    return 0.600000f;
                } else {
                    return 0.034483f;
                }
            }
        }
    } else {
        // f[6] = queue_len
        if (f[6] <= 8.500000f) {
            // f[3] = packet_loss_rate
            if (f[3] <= 36.500000f) {
                // f[7] = congestion_ratio
                if (f[7] <= 0.078125f) {
                    return 0.791925f;
                } else {
                    return 0.632327f;
                }
            } else {
                // f[11] = freshness_factor
                if (f[11] <= 0.025647f) {
                    return 0.843750f;
                } else {
                    return 0.496307f;
                }
            }
        } else {
            // f[5] = battery_voltage_mv
            if (f[5] <= 3659.500000f) {
                // f[3] = packet_loss_rate
                if (f[3] <= 0.500000f) {
                    return 0.733333f;
                } else {
                    return 0.169643f;
                }
            } else {
                // f[0] = rssi
                if (f[0] <= -115.500000f) {
                    return 0.176471f;
                } else {
                    return 0.411504f;
                }
            }
        }
    }
}

// Tree 1 (Max Depth 4)
static inline float eval_tree_1(const float *f) {
    // f[2] = link_margin
    if (f[2] <= 1.495000f) {
        // f[2] = link_margin
        if (f[2] <= 0.265000f) {
            // f[10] = last_seen_sec
            if (f[10] <= 3142.500000f) {
                // f[5] = battery_voltage_mv
                if (f[5] <= 3657.500000f) {
                    return 0.020979f;
                } else {
                    return 0.001885f;
                }
            } else {
                // f[3] = packet_loss_rate
                if (f[3] <= 82.500000f) {
                    return 0.000000f;
                } else {
                    return 0.375000f;
                }
            }
        } else {
            // f[10] = last_seen_sec
            if (f[10] <= 23.500000f) {
                // f[9] = hop_count
                if (f[9] <= 3.500000f) {
                    return 0.541667f;
                } else {
                    return 0.000000f;
                }
            } else {
                // f[2] = link_margin
                if (f[2] <= 0.775000f) {
                    return 0.058824f;
                } else {
                    return 0.000000f;
                }
            }
        }
    } else {
        // f[7] = congestion_ratio
        if (f[7] <= 0.296875f) {
            // f[5] = battery_voltage_mv
            if (f[5] <= 3312.500000f) {
                // f[4] = battery_pct
                if (f[4] <= 5.500000f) {
                    return 0.027778f;
                } else {
                    return 0.300000f;
                }
            } else {
                // f[1] = snr
                if (f[1] <= 13.265000f) {
                    return 0.543230f;
                } else {
                    return 0.712881f;
                }
            }
        } else {
            // f[6] = queue_len
            if (f[6] <= 25.500000f) {
                // f[0] = rssi
                if (f[0] <= -73.500000f) {
                    return 0.381323f;
                } else {
                    return 0.623377f;
                }
            } else {
                // f[3] = packet_loss_rate
                if (f[3] <= 77.500000f) {
                    return 0.197279f;
                } else {
                    return 0.066176f;
                }
            }
        }
    }
}

// Tree 2 (Max Depth 4)
static inline float eval_tree_2(const float *f) {
    // f[2] = link_margin
    if (f[2] <= 0.805000f) {
        // f[1] = snr
        if (f[1] <= -7.400000f) {
            // f[6] = queue_len
            if (f[6] <= 3.500000f) {
                // f[4] = battery_pct
                if (f[4] <= 41.500000f) {
                    return 0.114286f;
                } else {
                    return 0.007380f;
                }
            } else {
                // f[4] = battery_pct
                if (f[4] <= 48.500000f) {
                    return 0.008929f;
                } else {
                    return 0.000000f;
                }
            }
        } else {
            // f[8] = distance_m
            if (f[8] <= 1208.500000f) {
                // f[2] = link_margin
                if (f[2] <= 0.335000f) {
                    return 0.833333f;
                } else {
                    return 0.166667f;
                }
            } else {
                // f[9] = hop_count
                if (f[9] <= 3.500000f) {
                    return 0.000000f;
                } else {
                    return 0.333333f;
                }
            }
        }
    } else {
        // f[5] = battery_voltage_mv
        if (f[5] <= 3389.000000f) {
            // f[0] = rssi
            if (f[0] <= -69.000000f) {
                // f[11] = freshness_factor
                if (f[11] <= 0.839202f) {
                    return 0.081818f;
                } else {
                    return 0.393939f;
                }
            } else {
                return 0.833333f;
            }
        } else {
            // f[3] = packet_loss_rate
            if (f[3] <= 37.500000f) {
                // f[11] = freshness_factor
                if (f[11] <= 0.018956f) {
                    return 0.181818f;
                } else {
                    return 0.650386f;
                }
            } else {
                // f[5] = battery_voltage_mv
                if (f[5] <= 3614.500000f) {
                    return 0.237113f;
                } else {
                    return 0.406435f;
                }
            }
        }
    }
}

// Tree 3 (Max Depth 4)
static inline float eval_tree_3(const float *f) {
    // f[2] = link_margin
    if (f[2] <= 1.525000f) {
        // f[2] = link_margin
        if (f[2] <= 0.000000f) {
            // f[8] = distance_m
            if (f[8] <= 3021.500000f) {
                // f[11] = freshness_factor
                if (f[11] <= 0.960061f) {
                    return 0.002033f;
                } else {
                    return 0.030769f;
                }
            } else {
                // f[5] = battery_voltage_mv
                if (f[5] <= 4049.000000f) {
                    return 0.000000f;
                } else {
                    return 0.269231f;
                }
            }
        } else {
            // f[7] = congestion_ratio
            if (f[7] <= 0.015625f) {
                return 0.583333f;
            } else {
                // f[1] = snr
                if (f[1] <= -7.385000f) {
                    return 0.500000f;
                } else {
                    return 0.136364f;
                }
            }
        }
    } else {
        // f[0] = rssi
        if (f[0] <= -87.500000f) {
            // f[10] = last_seen_sec
            if (f[10] <= 22.500000f) {
                // f[3] = packet_loss_rate
                if (f[3] <= 71.500000f) {
                    return 0.493235f;
                } else {
                    return 0.181818f;
                }
            } else {
                // f[6] = queue_len
                if (f[6] <= 26.500000f) {
                    return 0.556136f;
                } else {
                    return 0.182692f;
                }
            }
        } else {
            // f[4] = battery_pct
            if (f[4] <= 11.500000f) {
                // f[5] = battery_voltage_mv
                if (f[5] <= 3147.000000f) {
                    return 0.000000f;
                } else {
                    return 0.333333f;
                }
            } else {
                // f[9] = hop_count
                if (f[9] <= 2.500000f) {
                    return 0.639286f;
                } else {
                    return 0.596026f;
                }
            }
        }
    }
}

// Tree 4 (Max Depth 4)
static inline float eval_tree_4(const float *f) {
    // f[1] = snr
    if (f[1] <= -6.030000f) {
        // f[1] = snr
        if (f[1] <= -6.810000f) {
            // f[7] = congestion_ratio
            if (f[7] <= 0.015625f) {
                // f[1] = snr
                if (f[1] <= -9.165000f) {
                    return 0.000000f;
                } else {
                    return 0.285714f;
                }
            } else {
                // f[6] = queue_len
                if (f[6] <= 4.500000f) {
                    return 0.005970f;
                } else {
                    return 0.000000f;
                }
            }
        } else {
            // f[9] = hop_count
            if (f[9] <= 1.500000f) {
                // f[2] = link_margin
                if (f[2] <= 1.050000f) {
                    return 0.000000f;
                } else {
                    return 0.166667f;
                }
            } else {
                // f[0] = rssi
                if (f[0] <= -123.000000f) {
                    return 0.090909f;
                } else {
                    return 0.571429f;
                }
            }
        }
    } else {
        // f[6] = queue_len
        if (f[6] <= 8.500000f) {
            // f[3] = packet_loss_rate
            if (f[3] <= 19.500000f) {
                // f[7] = congestion_ratio
                if (f[7] <= 0.046875f) {
                    return 0.904412f;
                } else {
                    return 0.674938f;
                }
            } else {
                // f[7] = congestion_ratio
                if (f[7] <= 0.109375f) {
                    return 0.633110f;
                } else {
                    return 0.497537f;
                }
            }
        } else {
            // f[7] = congestion_ratio
            if (f[7] <= 0.859375f) {
                // f[0] = rssi
                if (f[0] <= -98.500000f) {
                    return 0.301837f;
                } else {
                    return 0.511936f;
                }
            } else {
                // f[3] = packet_loss_rate
                if (f[3] <= 77.500000f) {
                    return 0.230159f;
                } else {
                    return 0.083333f;
                }
            }
        }
    }
}

// Tree 5 (Max Depth 4)
static inline float eval_tree_5(const float *f) {
    // f[1] = snr
    if (f[1] <= -6.005000f) {
        // f[5] = battery_voltage_mv
        if (f[5] <= 3106.000000f) {
            // f[11] = freshness_factor
            if (f[11] <= 0.794737f) {
                return 0.000000f;
            } else {
                return 0.500000f;
            }
        } else {
            // f[6] = queue_len
            if (f[6] <= 0.500000f) {
                // f[0] = rssi
                if (f[0] <= -124.500000f) {
                    return 0.000000f;
                } else {
                    return 0.875000f;
                }
            } else {
                // f[3] = packet_loss_rate
                if (f[3] <= 71.500000f) {
                    return 0.066667f;
                } else {
                    return 0.005828f;
                }
            }
        }
    } else {
        // f[6] = queue_len
        if (f[6] <= 9.500000f) {
            // f[1] = snr
            if (f[1] <= 14.930000f) {
                // f[11] = freshness_factor
                if (f[11] <= 0.022084f) {
                    return 0.900000f;
                } else {
                    return 0.468354f;
                }
            } else {
                // f[4] = battery_pct
                if (f[4] <= 13.500000f) {
                    return 0.155172f;
                } else {
                    return 0.700000f;
                }
            }
        } else {
            // f[4] = battery_pct
            if (f[4] <= 18.500000f) {
                // f[11] = freshness_factor
                if (f[11] <= 0.037037f) {
                    return 0.666667f;
                } else {
                    return 0.106796f;
                }
            } else {
                // f[3] = packet_loss_rate
                if (f[3] <= 27.500000f) {
                    return 0.530612f;
                } else {
                    return 0.288851f;
                }
            }
        }
    }
}

// Tree 6 (Max Depth 4)
static inline float eval_tree_6(const float *f) {
    // f[2] = link_margin
    if (f[2] <= 2.245000f) {
        // f[1] = snr
        if (f[1] <= -7.395000f) {
            // f[3] = packet_loss_rate
            if (f[3] <= 60.500000f) {
                return 0.142857f;
            } else {
                // f[11] = freshness_factor
                if (f[11] <= 0.944940f) {
                    return 0.004926f;
                } else {
                    return 0.039604f;
                }
            }
        } else {
            // f[7] = congestion_ratio
            if (f[7] <= 0.234375f) {
                // f[10] = last_seen_sec
                if (f[10] <= 4.500000f) {
                    return 0.900000f;
                } else {
                    return 0.175000f;
                }
            } else {
                // f[10] = last_seen_sec
                if (f[10] <= 5.500000f) {
                    return 0.500000f;
                } else {
                    return 0.036364f;
                }
            }
        }
    } else {
        // f[5] = battery_voltage_mv
        if (f[5] <= 3470.500000f) {
            // f[5] = battery_voltage_mv
            if (f[5] <= 3237.000000f) {
                // f[7] = congestion_ratio
                if (f[7] <= 0.328125f) {
                    return 0.147541f;
                } else {
                    return 0.000000f;
                }
            } else {
                // f[8] = distance_m
                if (f[8] <= 1283.500000f) {
                    return 0.230769f;
                } else {
                    return 0.647059f;
                }
            }
        } else {
            // f[0] = rssi
            if (f[0] <= -102.500000f) {
                // f[3] = packet_loss_rate
                if (f[3] <= 74.500000f) {
                    return 0.495413f;
                } else {
                    return 0.137681f;
                }
            } else {
                // f[6] = queue_len
                if (f[6] <= 9.500000f) {
                    return 0.692872f;
                } else {
                    return 0.417603f;
                }
            }
        }
    }
}

// Tree 7 (Max Depth 4)
static inline float eval_tree_7(const float *f) {
    // f[3] = packet_loss_rate
    if (f[3] <= 64.500000f) {
        // f[8] = distance_m
        if (f[8] <= 248.500000f) {
            // f[4] = battery_pct
            if (f[4] <= 9.000000f) {
                // f[7] = congestion_ratio
                if (f[7] <= 0.312500f) {
                    return 0.166667f;
                } else {
                    return 0.750000f;
                }
            } else {
                // f[7] = congestion_ratio
                if (f[7] <= 0.328125f) {
                    return 0.711165f;
                } else {
                    return 0.457746f;
                }
            }
        } else {
            // f[6] = queue_len
            if (f[6] <= 8.500000f) {
                // f[2] = link_margin
                if (f[2] <= 20.815000f) {
                    return 0.510965f;
                } else {
                    return 0.666124f;
                }
            } else {
                // f[6] = queue_len
                if (f[6] <= 23.500000f) {
                    return 0.396437f;
                } else {
                    return 0.133333f;
                }
            }
        }
    } else {
        // f[2] = link_margin
        if (f[2] <= 0.655000f) {
            // f[3] = packet_loss_rate
            if (f[3] <= 78.500000f) {
                // f[8] = distance_m
                if (f[8] <= 2989.000000f) {
                    return 0.004619f;
                } else {
                    return 0.027778f;
                }
            } else {
                return 0.000000f;
            }
        } else {
            // f[7] = congestion_ratio
            if (f[7] <= 0.984375f) {
                // f[7] = congestion_ratio
                if (f[7] <= 0.921875f) {
                    return 0.331148f;
                } else {
                    return 0.733333f;
                }
            } else {
                // f[0] = rssi
                if (f[0] <= -103.500000f) {
                    return 0.048077f;
                } else {
                    return 0.208955f;
                }
            }
        }
    }
}

// Tree 8 (Max Depth 4)
static inline float eval_tree_8(const float *f) {
    // f[1] = snr
    if (f[1] <= -6.005000f) {
        // f[2] = link_margin
        if (f[2] <= 0.100000f) {
            // f[7] = congestion_ratio
            if (f[7] <= 0.296875f) {
                // f[10] = last_seen_sec
                if (f[10] <= 344.500000f) {
                    return 0.006468f;
                } else {
                    return 0.076087f;
                }
            } else {
                return 0.000000f;
            }
        } else {
            // f[10] = last_seen_sec
            if (f[10] <= 23.500000f) {
                // f[4] = battery_pct
                if (f[4] <= 21.000000f) {
                    return 0.750000f;
                } else {
                    return 0.189189f;
                }
            } else {
                // f[5] = battery_voltage_mv
                if (f[5] <= 3779.500000f) {
                    return 0.000000f;
                } else {
                    return 0.142857f;
                }
            }
        }
    } else {
        // f[5] = battery_voltage_mv
        if (f[5] <= 3327.000000f) {
            // f[6] = queue_len
            if (f[6] <= 2.500000f) {
                // f[2] = link_margin
                if (f[2] <= 13.200000f) {
                    return 0.181818f;
                } else {
                    return 0.600000f;
                }
            } else {
                // f[10] = last_seen_sec
                if (f[10] <= 7.500000f) {
                    return 0.333333f;
                } else {
                    return 0.067416f;
                }
            }
        } else {
            // f[8] = distance_m
            if (f[8] <= 553.500000f) {
                // f[7] = congestion_ratio
                if (f[7] <= 0.296875f) {
                    return 0.675641f;
                } else {
                    return 0.377451f;
                }
            } else {
                // f[2] = link_margin
                if (f[2] <= 9.470000f) {
                    return 0.354467f;
                } else {
                    return 0.498433f;
                }
            }
        }
    }
}

// Tree 9 (Max Depth 4)
static inline float eval_tree_9(const float *f) {
    // f[4] = battery_pct
    if (f[4] <= 11.500000f) {
        // f[6] = queue_len
        if (f[6] <= 0.500000f) {
            // f[11] = freshness_factor
            if (f[11] <= 0.714691f) {
                return 0.800000f;
            } else {
                return 0.100000f;
            }
        } else {
            // f[0] = rssi
            if (f[0] <= -115.500000f) {
                // f[0] = rssi
                if (f[0] <= -124.500000f) {
                    return 0.000000f;
                } else {
                    return 0.068966f;
                }
            } else {
                // f[1] = snr
                if (f[1] <= 10.655000f) {
                    return 0.333333f;
                } else {
                    return 0.096386f;
                }
            }
        }
    } else {
        // f[1] = snr
        if (f[1] <= -4.660000f) {
            // f[0] = rssi
            if (f[0] <= -120.500000f) {
                // f[4] = battery_pct
                if (f[4] <= 31.500000f) {
                    return 0.000000f;
                } else {
                    return 0.027327f;
                }
            } else {
                return 0.875000f;
            }
        } else {
            // f[2] = link_margin
            if (f[2] <= 19.395000f) {
                // f[3] = packet_loss_rate
                if (f[3] <= 75.500000f) {
                    return 0.486983f;
                } else {
                    return 0.125000f;
                }
            } else {
                // f[7] = congestion_ratio
                if (f[7] <= 0.296875f) {
                    return 0.701214f;
                } else {
                    return 0.400722f;
                }
            }
        }
    }
}

// Tree 10 (Max Depth 4)
static inline float eval_tree_10(const float *f) {
    // f[0] = rssi
    if (f[0] <= -121.500000f) {
        // f[3] = packet_loss_rate
        if (f[3] <= 67.500000f) {
            // f[2] = link_margin
            if (f[2] <= -0.700000f) {
                return 0.000000f;
            } else {
                // f[8] = distance_m
                if (f[8] <= 2157.000000f) {
                    return 0.464286f;
                } else {
                    return 0.000000f;
                }
            }
        } else {
            // f[1] = snr
            if (f[1] <= -6.815000f) {
                // f[10] = last_seen_sec
                if (f[10] <= 281.500000f) {
                    return 0.002500f;
                } else {
                    return 0.037594f;
                }
            } else {
                // f[8] = distance_m
                if (f[8] <= 2708.000000f) {
                    return 0.098361f;
                } else {
                    return 0.346154f;
                }
            }
        }
    } else {
        // f[4] = battery_pct
        if (f[4] <= 11.500000f) {
            // f[11] = freshness_factor
            if (f[11] <= 0.902307f) {
                // f[6] = queue_len
                if (f[6] <= 16.000000f) {
                    return 0.194915f;
                } else {
                    return 0.000000f;
                }
            } else {
                // f[7] = congestion_ratio
                if (f[7] <= 0.203125f) {
                    return 0.875000f;
                } else {
                    return 0.375000f;
                }
            }
        } else {
            // f[6] = queue_len
            if (f[6] <= 19.000000f) {
                // f[2] = link_margin
                if (f[2] <= 21.265000f) {
                    return 0.474443f;
                } else {
                    return 0.666667f;
                }
            } else {
                // f[11] = freshness_factor
                if (f[11] <= 0.745370f) {
                    return 0.275168f;
                } else {
                    return 0.162500f;
                }
            }
        }
    }
}

// Tree 11 (Max Depth 4)
static inline float eval_tree_11(const float *f) {
    // f[8] = distance_m
    if (f[8] <= 936.500000f) {
        // f[1] = snr
        if (f[1] <= -5.580000f) {
            // f[2] = link_margin
            if (f[2] <= 0.050000f) {
                // f[3] = packet_loss_rate
                if (f[3] <= 71.500000f) {
                    return 0.080645f;
                } else {
                    return 0.002618f;
                }
            } else {
                // f[6] = queue_len
                if (f[6] <= 7.500000f) {
                    return 0.307692f;
                } else {
                    return 0.000000f;
                }
            }
        } else {
            // f[5] = battery_voltage_mv
            if (f[5] <= 3467.000000f) {
                // f[5] = battery_voltage_mv
                if (f[5] <= 3239.000000f) {
                    return 0.082192f;
                } else {
                    return 0.285714f;
                }
            } else {
                // f[7] = congestion_ratio
                if (f[7] <= 0.421875f) {
                    return 0.608634f;
                } else {
                    return 0.183036f;
                }
            }
        }
    } else {
        // f[3] = packet_loss_rate
        if (f[3] <= 66.500000f) {
            // f[0] = rssi
            if (f[0] <= -109.500000f) {
                // f[2] = link_margin
                if (f[2] <= 0.225000f) {
                    return 0.000000f;
                } else {
                    return 0.385000f;
                }
            } else {
                // f[3] = packet_loss_rate
                if (f[3] <= 37.500000f) {
                    return 0.647541f;
                } else {
                    return 0.427536f;
                }
            }
        } else {
            // f[3] = packet_loss_rate
            if (f[3] <= 67.500000f) {
                // f[6] = queue_len
                if (f[6] <= 2.500000f) {
                    return 0.000000f;
                } else {
                    return 0.320000f;
                }
            } else {
                // f[1] = snr
                if (f[1] <= -6.745000f) {
                    return 0.002212f;
                } else {
                    return 0.194030f;
                }
            }
        }
    }
}


float route_scorer_predict_features(const float *features) {
    float sum = 0.0f;
    sum += eval_tree_0(features);
    sum += eval_tree_1(features);
    sum += eval_tree_2(features);
    sum += eval_tree_3(features);
    sum += eval_tree_4(features);
    sum += eval_tree_5(features);
    sum += eval_tree_6(features);
    sum += eval_tree_7(features);
    sum += eval_tree_8(features);
    sum += eval_tree_9(features);
    sum += eval_tree_10(features);
    sum += eval_tree_11(features);
    return sum / 12.0f;
}

float route_scorer_predict_packet(const ForestLinkTelemetryPacket *pkt) {
    float features[ROUTE_SCORER_NUM_FEATURES];
    route_scorer_extract_features(pkt, features);
    return route_scorer_predict_features(features);
}
