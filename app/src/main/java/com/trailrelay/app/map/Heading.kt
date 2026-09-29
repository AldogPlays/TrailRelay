package com.trailrelay.app.map

/** East-positive declination converts magnetic azimuth to geographic map bearing. */
fun trueHeading(magnetic: Float, declination: Float): Float =
    ((magnetic + declination) % 360f + 360f) % 360f
