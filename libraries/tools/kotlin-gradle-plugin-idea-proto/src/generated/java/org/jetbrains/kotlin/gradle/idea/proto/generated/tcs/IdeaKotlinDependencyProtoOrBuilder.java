// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: proto_tcs.proto

package org.jetbrains.kotlin.gradle.idea.proto.generated.tcs;

public interface IdeaKotlinDependencyProtoOrBuilder extends
    // @@protoc_insertion_point(interface_extends:org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinDependencyProto)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>.org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinSourceDependencyProto source_dependency = 1;</code>
   * @return Whether the sourceDependency field is set.
   */
  boolean hasSourceDependency();
  /**
   * <code>.org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinSourceDependencyProto source_dependency = 1;</code>
   * @return The sourceDependency.
   */
  org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinSourceDependencyProto getSourceDependency();
  /**
   * <code>.org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinSourceDependencyProto source_dependency = 1;</code>
   */
  org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinSourceDependencyProtoOrBuilder getSourceDependencyOrBuilder();

  /**
   * <code>.org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinResolvedBinaryDependencyProto resolved_binary_dependency = 2;</code>
   * @return Whether the resolvedBinaryDependency field is set.
   */
  boolean hasResolvedBinaryDependency();
  /**
   * <code>.org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinResolvedBinaryDependencyProto resolved_binary_dependency = 2;</code>
   * @return The resolvedBinaryDependency.
   */
  org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinResolvedBinaryDependencyProto getResolvedBinaryDependency();
  /**
   * <code>.org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinResolvedBinaryDependencyProto resolved_binary_dependency = 2;</code>
   */
  org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinResolvedBinaryDependencyProtoOrBuilder getResolvedBinaryDependencyOrBuilder();

  /**
   * <code>.org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinUnresolvedBinaryDependencyProto unresolved_binary_dependency = 3;</code>
   * @return Whether the unresolvedBinaryDependency field is set.
   */
  boolean hasUnresolvedBinaryDependency();
  /**
   * <code>.org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinUnresolvedBinaryDependencyProto unresolved_binary_dependency = 3;</code>
   * @return The unresolvedBinaryDependency.
   */
  org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinUnresolvedBinaryDependencyProto getUnresolvedBinaryDependency();
  /**
   * <code>.org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinUnresolvedBinaryDependencyProto unresolved_binary_dependency = 3;</code>
   */
  org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinUnresolvedBinaryDependencyProtoOrBuilder getUnresolvedBinaryDependencyOrBuilder();

  public org.jetbrains.kotlin.gradle.idea.proto.generated.tcs.IdeaKotlinDependencyProto.DependencyCase getDependencyCase();
}
