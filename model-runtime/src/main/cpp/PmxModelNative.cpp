#include <jni.h>
#include <assimp/Importer.hpp>
#include <assimp/scene.h>
#include <assimp/postprocess.h>
#include <assimp/material.h>
#include <algorithm>
#include <string>
#include <vector>

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_reverseassistant_mmd_PmxModelNative_loadTriangleVertices(
        JNIEnv *env, jobject, jstring modelPath) {
    if (modelPath == nullptr) return nullptr;
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) return nullptr;

    Assimp::Importer importer;
    const aiScene *scene = importer.ReadFile(
        path,
        aiProcess_Triangulate |
        aiProcess_GenSmoothNormals |
        aiProcess_JoinIdenticalVertices |
        aiProcess_PreTransformVertices |
        aiProcess_ValidateDataStructure
    );
    env->ReleaseStringUTFChars(modelPath, path);
    if (scene == nullptr || !scene->HasMeshes()) return nullptr;

    std::vector<float> output;
    constexpr size_t MAX_FLOATS = 12U * 1024U * 1024U;
    for (unsigned int meshIndex = 0; meshIndex < scene->mNumMeshes; ++meshIndex) {
        const aiMesh *mesh = scene->mMeshes[meshIndex];
        if (mesh == nullptr || !mesh->HasPositions()) continue;

        aiColor4D diffuse(0.82f, 0.78f, 0.88f, 1.0f);
        if (mesh->mMaterialIndex < scene->mNumMaterials) {
            aiGetMaterialColor(
                scene->mMaterials[mesh->mMaterialIndex],
                AI_MATKEY_COLOR_DIFFUSE,
                &diffuse
            );
        }

        for (unsigned int faceIndex = 0; faceIndex < mesh->mNumFaces; ++faceIndex) {
            const aiFace &face = mesh->mFaces[faceIndex];
            if (face.mNumIndices != 3) continue;
            for (unsigned int corner = 0; corner < 3; ++corner) {
                const unsigned int index = face.mIndices[corner];
                if (index >= mesh->mNumVertices) continue;
                const aiVector3D &p = mesh->mVertices[index];
                const aiVector3D n = mesh->HasNormals() ? mesh->mNormals[index] : aiVector3D(0, 1, 0);
                output.insert(output.end(), {
                    p.x, p.y, p.z,
                    n.x, n.y, n.z,
                    diffuse.r, diffuse.g, diffuse.b
                });
                if (output.size() > MAX_FLOATS) return nullptr;
            }
        }
    }

    if (output.empty() || output.size() % 9U != 0U) return nullptr;
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(output.size()));
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    return result;
}
