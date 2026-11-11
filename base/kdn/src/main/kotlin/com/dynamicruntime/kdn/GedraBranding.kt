package com.dynamicruntime.kdn

/**
 * Gedra's web resources package (issues #1769, #1779): the images a reference deployment presents the product with,
 * which the `reference` flavor names as the deployment's default ([KdnComponent.flavors]). Today its link-preview image
 * alone, rendered from the `share-image.svg` beside it under `web-resources/gedra/`; every other slot is the built-in.
 */
@Suppress("ConstPropertyName")
object GedraBranding {
    /** The package's id. */
    const val webResources = "kdr:gedra"

    /** The file of its share image, under `web-resources/`. */
    const val shareImage = "gedra/share-image.png"
}
