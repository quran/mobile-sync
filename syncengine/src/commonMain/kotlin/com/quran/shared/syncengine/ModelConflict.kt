package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation

data class ModelConflict<Model>(val remoteModelMutation: RemoteModelMutation<Model>,
                                val localModelMutation: LocalModelMutation<Model>) {

}