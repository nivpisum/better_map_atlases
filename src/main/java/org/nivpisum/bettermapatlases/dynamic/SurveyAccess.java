/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic;

/** Presence of a survey identifies a dynamic map; surface pixels remain in the ordinary map layer. */
public interface SurveyAccess {
    SurveyLayer accurateMaps$getSurvey(boolean create);

    /** Installs an independently owned copy; null is used only for explicit client cache eviction. */
    void accurateMaps$setSurvey(SurveyLayer layer);
}
