/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.api;

/** Texture coordinates derived from the current native mesh, using RuneLite's texture basis. */
final class ModelTextureUvs
{
    private ModelTextureUvs() { }

    static float[] compute(Model model)
    {
        short[] textures = model.getFaceTextures();
        if (textures == null) return null;
        int faces = model.getFaceCount();
        float[] coordinates = new float[Math.multiplyExact(faces, 6)];
        float[] u = new float[3], v = new float[3];
        for (int face = 0; face < faces; face++)
        {
            if (textures[face] == -1) continue;
            computeFaceUvs(model, face, u, v);
            for (int vertex = 0; vertex < 3; vertex++)
            {
                coordinates[face * 6 + vertex * 2] = u[vertex];
                coordinates[face * 6 + vertex * 2 + 1] = v[vertex];
            }
        }
        return coordinates;
    }

	static void computeFaceUvs(Model model, int face, float[] u, float[] v)
	{
		final float[] vertexX = model.getVerticesX();
		final float[] vertexY = model.getVerticesY();
		final float[] vertexZ = model.getVerticesZ();

		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final byte[] textureFaces = model.getTextureFaces();
		final int[] texIndices1 = model.getTexIndices1();
		final int[] texIndices2 = model.getTexIndices2();
		final int[] texIndices3 = model.getTexIndices3();

		if (textureFaces != null && textureFaces[face] != -1)
		{
			final int triangleA = indices1[face];
			final int triangleB = indices2[face];
			final int triangleC = indices3[face];

			int tfaceIdx = textureFaces[face] & 0xff;
			int texA = texIndices1[tfaceIdx];
			int texB = texIndices2[tfaceIdx];
			int texC = texIndices3[tfaceIdx];

			// v1 = vertex[texA]
			float v1x = vertexX[texA];
			float v1y = vertexY[texA];
			float v1z = vertexZ[texA];
			// v2 = vertex[texB] - v1
			float v2x = vertexX[texB] - v1x;
			float v2y = vertexY[texB] - v1y;
			float v2z = vertexZ[texB] - v1z;
			// v3 = vertex[texC] - v1
			float v3x = vertexX[texC] - v1x;
			float v3y = vertexY[texC] - v1y;
			float v3z = vertexZ[texC] - v1z;

			// v4 = vertex[triangleA] - v1
			float v4x = vertexX[triangleA] - v1x;
			float v4y = vertexY[triangleA] - v1y;
			float v4z = vertexZ[triangleA] - v1z;
			// v5 = vertex[triangleB] - v1
			float v5x = vertexX[triangleB] - v1x;
			float v5y = vertexY[triangleB] - v1y;
			float v5z = vertexZ[triangleB] - v1z;
			// v6 = vertex[triangleC] - v1
			float v6x = vertexX[triangleC] - v1x;
			float v6y = vertexY[triangleC] - v1y;
			float v6z = vertexZ[triangleC] - v1z;

			// v7 = v2 x v3
			float v7x = v2y * v3z - v2z * v3y;
			float v7y = v2z * v3x - v2x * v3z;
			float v7z = v2x * v3y - v2y * v3x;

			// v8 = v3 x v7
			float v8x = v3y * v7z - v3z * v7y;
			float v8y = v3z * v7x - v3x * v7z;
			float v8z = v3x * v7y - v3y * v7x;

			// f = 1 / (v8 ⋅ v2)
			float f = 1.0F / (v8x * v2x + v8y * v2y + v8z * v2z);

			// u0 = (v8 ⋅ v4) * f
			u[0] = (v8x * v4x + v8y * v4y + v8z * v4z) * f;
			// u1 = (v8 ⋅ v5) * f
			u[1] = (v8x * v5x + v8y * v5y + v8z * v5z) * f;
			// u2 = (v8 ⋅ v6) * f
			u[2] = (v8x * v6x + v8y * v6y + v8z * v6z) * f;

			// v8 = v2 x v7
			v8x = v2y * v7z - v2z * v7y;
			v8y = v2z * v7x - v2x * v7z;
			v8z = v2x * v7y - v2y * v7x;

			// f = 1 / (v8 ⋅ v3)
			f = 1.0F / (v8x * v3x + v8y * v3y + v8z * v3z);

			// v0 = (v8 ⋅ v4) * f
			v[0] = (v8x * v4x + v8y * v4y + v8z * v4z) * f;
			// v1 = (v8 ⋅ v5) * f
			v[1] = (v8x * v5x + v8y * v5y + v8z * v5z) * f;
			// v2 = (v8 ⋅ v6) * f
			v[2] = (v8x * v6x + v8y * v6y + v8z * v6z) * f;
		}
		else
		{
			// Without a texture face, the client assigns tex = triangle, but the resulting
			// calculations can be reduced:
			//
			// v1 = vertex[texA]
			// v2 = vertex[texB] - v1
			// v3 = vertex[texC] - v1
			//
			// v4 = 0
			// v5 = v2
			// v6 = v3
			//
			// v7 = v2 x v3
			//
			// v8 = v3 x v7
			// u0 = (v8 . v4) / (v8 . v2) // 0 because v4 is 0
			// u1 = (v8 . v5) / (v8 . v2) // 1 because v5=v2
			// u2 = (v8 . v6) / (v8 . v2) // 0 because v8 is perpendicular to v3/v6
			//
			// v8 = v2 x v7
			// v0 = (v8 . v4) / (v8 ⋅ v3) // 0 because v4 is 0
			// v1 = (v8 . v5) / (v8 ⋅ v3) // 0 because v8 is perpendicular to v5/v2
			// v2 = (v8 . v6) / (v8 ⋅ v3) // 1 because v6=v3

			u[0] = 0f;
			v[0] = 0f;

			u[1] = 1f;
			v[1] = 0f;

			u[2] = 0f;
			v[2] = 1f;
		}
	}
}
