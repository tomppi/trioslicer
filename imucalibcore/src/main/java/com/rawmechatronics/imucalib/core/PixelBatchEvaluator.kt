package com.rawmechatronics.imucalib.core

/**
 * Optional batch evaluator for the pixel reprojection residuals and their Jacobian
 * with respect to the local spline coefficients. The reference implementation in
 * [KalibrCalibrator] perturbs each coefficient and re-evaluates the projection; a
 * GPU implementation can compute the same 2x36 analytic Jacobian for all
 * observations in one pass. Results are in observation order, each observation
 * contributing [2 + 2*localCount] values: residual u, residual v, then the row of
 * du/dc, then the row of dv/dc.
 */
interface PixelBatchEvaluator {
    fun evaluate(
        coeff: DoubleArray,
        base: IntArray,
        basis: DoubleArray,
        xyz: DoubleArray,
        uv: DoubleArray,
        nObs: Int,
        localCount: Int,
        camera: PinholeModel,
        sigma: Double,
    ): DoubleArray
}
