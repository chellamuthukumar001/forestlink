#include "node_health_model.h"
#include <string.h>

// ================= Transpiled Health Trees =================
static inline void eval_health_tree_0(const float *f, float *out_probs) {
    // f[1] = battery_pct
    if (f[1] <= 48.900000f) {
        // f[0] = battery_voltage_mv
        if (f[0] <= 3443.500000f) {
            // f[4] = rssi_variance
            if (f[4] <= 18.345000f) {
                out_probs[0] += 0.000000f;
                out_probs[1] += 1.000000f;
                out_probs[2] += 0.000000f;
            } else {
                out_probs[0] += 0.000000f;
                out_probs[1] += 0.000000f;
                out_probs[2] += 1.000000f;
            }
        } else {
            // f[1] = battery_pct
            if (f[1] <= 17.500000f) {
                out_probs[0] += 0.000000f;
                out_probs[1] += 0.000000f;
                out_probs[2] += 1.000000f;
            } else {
                out_probs[0] += 0.000000f;
                out_probs[1] += 1.000000f;
                out_probs[2] += 0.000000f;
            }
        }
    } else {
        out_probs[0] += 1.000000f;
        out_probs[1] += 0.000000f;
        out_probs[2] += 0.000000f;
    }
}

static inline void eval_health_tree_1(const float *f, float *out_probs) {
    // f[2] = dv_dt_mv_per_hr
    if (f[2] <= -16.510000f) {
        // f[2] = dv_dt_mv_per_hr
        if (f[2] <= -47.625000f) {
            out_probs[0] += 0.000000f;
            out_probs[1] += 0.000000f;
            out_probs[2] += 1.000000f;
        } else {
            out_probs[0] += 0.000000f;
            out_probs[1] += 1.000000f;
            out_probs[2] += 0.000000f;
        }
    } else {
        out_probs[0] += 1.000000f;
        out_probs[1] += 0.000000f;
        out_probs[2] += 0.000000f;
    }
}

static inline void eval_health_tree_2(const float *f, float *out_probs) {
    // f[4] = rssi_variance
    if (f[4] <= 7.030000f) {
        out_probs[0] += 1.000000f;
        out_probs[1] += 0.000000f;
        out_probs[2] += 0.000000f;
    } else {
        // f[5] = missed_heartbeats
        if (f[5] <= 4.500000f) {
            // f[3] = temperature_c
            if (f[3] <= 47.049999f) {
                // f[2] = dv_dt_mv_per_hr
                if (f[2] <= -52.789999f) {
                    out_probs[0] += 0.000000f;
                    out_probs[1] += 0.000000f;
                    out_probs[2] += 1.000000f;
                } else {
                    out_probs[0] += 0.000000f;
                    out_probs[1] += 1.000000f;
                    out_probs[2] += 0.000000f;
                }
            } else {
                out_probs[0] += 0.000000f;
                out_probs[1] += 0.000000f;
                out_probs[2] += 1.000000f;
            }
        } else {
            out_probs[0] += 0.000000f;
            out_probs[1] += 0.000000f;
            out_probs[2] += 1.000000f;
        }
    }
}

static inline void eval_health_tree_3(const float *f, float *out_probs) {
    // f[2] = dv_dt_mv_per_hr
    if (f[2] <= -16.510000f) {
        // f[0] = battery_voltage_mv
        if (f[0] <= 3450.000000f) {
            // f[2] = dv_dt_mv_per_hr
            if (f[2] <= -46.570000f) {
                out_probs[0] += 0.000000f;
                out_probs[1] += 0.000000f;
                out_probs[2] += 1.000000f;
            } else {
                out_probs[0] += 0.000000f;
                out_probs[1] += 1.000000f;
                out_probs[2] += 0.000000f;
            }
        } else {
            // f[4] = rssi_variance
            if (f[4] <= 23.070000f) {
                out_probs[0] += 0.000000f;
                out_probs[1] += 1.000000f;
                out_probs[2] += 0.000000f;
            } else {
                out_probs[0] += 0.000000f;
                out_probs[1] += 0.000000f;
                out_probs[2] += 1.000000f;
            }
        }
    } else {
        out_probs[0] += 1.000000f;
        out_probs[1] += 0.000000f;
        out_probs[2] += 0.000000f;
    }
}

static inline void eval_health_tree_4(const float *f, float *out_probs) {
    // f[0] = battery_voltage_mv
    if (f[0] <= 3751.500000f) {
        // f[2] = dv_dt_mv_per_hr
        if (f[2] <= -47.625000f) {
            out_probs[0] += 0.000000f;
            out_probs[1] += 0.000000f;
            out_probs[2] += 1.000000f;
        } else {
            // f[2] = dv_dt_mv_per_hr
            if (f[2] <= -15.905000f) {
                out_probs[0] += 0.000000f;
                out_probs[1] += 1.000000f;
                out_probs[2] += 0.000000f;
            } else {
                out_probs[0] += 1.000000f;
                out_probs[1] += 0.000000f;
                out_probs[2] += 0.000000f;
            }
        }
    } else {
        // f[1] = battery_pct
        if (f[1] <= 49.000000f) {
            out_probs[0] += 0.000000f;
            out_probs[1] += 1.000000f;
            out_probs[2] += 0.000000f;
        } else {
            out_probs[0] += 1.000000f;
            out_probs[1] += 0.000000f;
            out_probs[2] += 0.000000f;
        }
    }
}

static inline void eval_health_tree_5(const float *f, float *out_probs) {
    // f[1] = battery_pct
    if (f[1] <= 48.950001f) {
        // f[2] = dv_dt_mv_per_hr
        if (f[2] <= -47.680000f) {
            out_probs[0] += 0.000000f;
            out_probs[1] += 0.000000f;
            out_probs[2] += 1.000000f;
        } else {
            out_probs[0] += 0.000000f;
            out_probs[1] += 1.000000f;
            out_probs[2] += 0.000000f;
        }
    } else {
        out_probs[0] += 1.000000f;
        out_probs[1] += 0.000000f;
        out_probs[2] += 0.000000f;
    }
}

static inline void eval_health_tree_6(const float *f, float *out_probs) {
    // f[4] = rssi_variance
    if (f[4] <= 7.025000f) {
        out_probs[0] += 1.000000f;
        out_probs[1] += 0.000000f;
        out_probs[2] += 0.000000f;
    } else {
        // f[0] = battery_voltage_mv
        if (f[0] <= 3440.500000f) {
            // f[1] = battery_pct
            if (f[1] <= 17.549999f) {
                out_probs[0] += 0.000000f;
                out_probs[1] += 0.000000f;
                out_probs[2] += 1.000000f;
            } else {
                out_probs[0] += 0.000000f;
                out_probs[1] += 1.000000f;
                out_probs[2] += 0.000000f;
            }
        } else {
            // f[5] = missed_heartbeats
            if (f[5] <= 5.000000f) {
                // f[3] = temperature_c
                if (f[3] <= 48.549999f) {
                    out_probs[0] += 0.000000f;
                    out_probs[1] += 1.000000f;
                    out_probs[2] += 0.000000f;
                } else {
                    out_probs[0] += 0.000000f;
                    out_probs[1] += 0.000000f;
                    out_probs[2] += 1.000000f;
                }
            } else {
                out_probs[0] += 0.000000f;
                out_probs[1] += 0.000000f;
                out_probs[2] += 1.000000f;
            }
        }
    }
}

static inline void eval_health_tree_7(const float *f, float *out_probs) {
    // f[0] = battery_voltage_mv
    if (f[0] <= 3752.500000f) {
        // f[2] = dv_dt_mv_per_hr
        if (f[2] <= -47.680000f) {
            out_probs[0] += 0.000000f;
            out_probs[1] += 0.000000f;
            out_probs[2] += 1.000000f;
        } else {
            // f[1] = battery_pct
            if (f[1] <= 48.949999f) {
                out_probs[0] += 0.000000f;
                out_probs[1] += 1.000000f;
                out_probs[2] += 0.000000f;
            } else {
                out_probs[0] += 1.000000f;
                out_probs[1] += 0.000000f;
                out_probs[2] += 0.000000f;
            }
        }
    } else {
        // f[1] = battery_pct
        if (f[1] <= 48.950001f) {
            out_probs[0] += 0.000000f;
            out_probs[1] += 1.000000f;
            out_probs[2] += 0.000000f;
        } else {
            out_probs[0] += 1.000000f;
            out_probs[1] += 0.000000f;
            out_probs[2] += 0.000000f;
        }
    }
}

static inline void eval_health_tree_8(const float *f, float *out_probs) {
    // f[2] = dv_dt_mv_per_hr
    if (f[2] <= -16.540000f) {
        // f[0] = battery_voltage_mv
        if (f[0] <= 3450.000000f) {
            // f[4] = rssi_variance
            if (f[4] <= 20.134999f) {
                out_probs[0] += 0.000000f;
                out_probs[1] += 1.000000f;
                out_probs[2] += 0.000000f;
            } else {
                out_probs[0] += 0.000000f;
                out_probs[1] += 0.000000f;
                out_probs[2] += 1.000000f;
            }
        } else {
            // f[5] = missed_heartbeats
            if (f[5] <= 6.000000f) {
                out_probs[0] += 0.000000f;
                out_probs[1] += 1.000000f;
                out_probs[2] += 0.000000f;
            } else {
                out_probs[0] += 0.000000f;
                out_probs[1] += 0.000000f;
                out_probs[2] += 1.000000f;
            }
        }
    } else {
        out_probs[0] += 1.000000f;
        out_probs[1] += 0.000000f;
        out_probs[2] += 0.000000f;
    }
}

static inline void eval_health_tree_9(const float *f, float *out_probs) {
    // f[4] = rssi_variance
    if (f[4] <= 7.005000f) {
        out_probs[0] += 1.000000f;
        out_probs[1] += 0.000000f;
        out_probs[2] += 0.000000f;
    } else {
        // f[4] = rssi_variance
        if (f[4] <= 22.000000f) {
            // f[1] = battery_pct
            if (f[1] <= 17.350000f) {
                out_probs[0] += 0.000000f;
                out_probs[1] += 0.000000f;
                out_probs[2] += 1.000000f;
            } else {
                out_probs[0] += 0.000000f;
                out_probs[1] += 1.000000f;
                out_probs[2] += 0.000000f;
            }
        } else {
            out_probs[0] += 0.000000f;
            out_probs[1] += 0.000000f;
            out_probs[2] += 1.000000f;
        }
    }
}


NodeHealthResult node_health_predict(
    uint16_t battery_mv,
    uint8_t battery_pct,
    float dv_dt_mv_per_hr,
    float temperature_c,
    float rssi_variance,
    uint8_t missed_heartbeats
) {
    float f[6];
    f[0] = (float)battery_mv;
    f[1] = (float)battery_pct;
    f[2] = dv_dt_mv_per_hr;
    f[3] = temperature_c;
    f[4] = rssi_variance;
    f[5] = (float)missed_heartbeats;

    float probs[3] = {0.0f, 0.0f, 0.0f};
    eval_health_tree_0(f, probs);
    eval_health_tree_1(f, probs);
    eval_health_tree_2(f, probs);
    eval_health_tree_3(f, probs);
    eval_health_tree_4(f, probs);
    eval_health_tree_5(f, probs);
    eval_health_tree_6(f, probs);
    eval_health_tree_7(f, probs);
    eval_health_tree_8(f, probs);
    eval_health_tree_9(f, probs);

    probs[0] /= 10.0f;
    probs[1] /= 10.0f;
    probs[2] /= 10.0f;

    int best_class = 0;
    float max_p = probs[0];
    for (int i = 1; i < 3; i++) {
        if (probs[i] > max_p) {
            max_p = probs[i];
            best_class = i;
        }
    }

    NodeHealthResult res;
    res.status = (NodeHealthStatus)best_class;
    res.confidence = max_p;

    if (best_class == 0) {
        res.status_str = "HEALTHY";
        res.hours_remaining_est = (dv_dt_mv_per_hr < -0.1f) ? (float)(battery_mv - 3300) / (-dv_dt_mv_per_hr) : 48.0f;
    } else if (best_class == 1) {
        res.status_str = "DEGRADING";
        res.hours_remaining_est = (dv_dt_mv_per_hr < -0.1f) ? (float)(battery_mv - 3250) / (-dv_dt_mv_per_hr) : 8.0f;
    } else {
        res.status_str = "CRITICAL";
        res.hours_remaining_est = (dv_dt_mv_per_hr < -0.1f) ? (float)(battery_mv - 3100) / (-dv_dt_mv_per_hr) : 1.0f;
    }

    if (res.hours_remaining_est < 0.1f) res.hours_remaining_est = 0.1f;
    if (res.hours_remaining_est > 72.0f) res.hours_remaining_est = 72.0f;

    return res;
}
