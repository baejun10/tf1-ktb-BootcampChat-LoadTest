import React from 'react';

/**
 * 파일 메시지 로딩 상태를 표시하는 Skeleton Loader
 * 
 * Props:
 * - type: 'image' | 'video' | 'audio' | 'document' (기본값: 'image')
 * - isLoading: boolean (기본값: true)
 * 
 * Test ID: file-skeleton-{type}
 */
const FileSkeleton = ({ type = 'image', isLoading = true }) => {
  if (!isLoading) return null;

  if (type === 'image') {
    return (
      <div className="animate-pulse" data-testid="file-skeleton-image">
        {/* 이미지 미리보기 스켈레톤 */}
        <div className="w-[400px] h-[400px] bg-gray-800 rounded-md flex items-center justify-center">
          <div className="w-12 h-12 bg-gray-700 rounded-full animate-spin opacity-50" />
        </div>

        {/* 파일 정보 스켈레톤 */}
        <div className="mt-2 space-y-2">
          <div className="h-4 bg-gray-700 rounded w-3/4" />
          <div className="h-3 bg-gray-800 rounded w-1/4" />
        </div>

        {/* 버튼 영역 스켈레톤 */}
        <div className="mt-3 flex gap-2">
          <div className="h-8 bg-gray-700 rounded flex-1" />
          <div className="h-8 bg-gray-700 rounded flex-1" />
        </div>
      </div>
    );
  }

  if (type === 'video') {
    return (
      <div className="animate-pulse" data-testid="file-skeleton-video">
        <div className="w-[400px] h-[400px] bg-gray-800 rounded-md flex items-center justify-center">
          <div className="w-16 h-16 bg-gray-700 rounded-full flex items-center justify-center">
            <div className="w-8 h-8 bg-gray-600 rounded-full" />
          </div>
        </div>
        {/* 파일 정보 스켈레톤 (동일) ... */}
      </div>
    );
  }

  if (type === 'audio') {
    return (
      <div className="animate-pulse" data-testid="file-skeleton-audio">
        {/* 오디오 플레이어 형태의 스켈레톤 */}
        <div className="h-10 bg-gray-800 rounded flex items-center px-3 gap-2">
          <div className="w-6 h-6 bg-gray-700 rounded" />
          <div className="flex-1 h-1 bg-gray-700 rounded" />
          <div className="w-10 h-6 bg-gray-700 rounded" />
        </div>
        {/* 파일 정보 스켈레톤 (동일) ... */}
      </div>
    );
  }

  if (type === 'document') {
    return (
      <div className="animate-pulse" data-testid="file-skeleton-document">
        {/* 파일 아이콘 형태의 스켈레톤 */}
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 bg-gray-700 rounded" />
          <div className="flex-1 space-y-2">
            <div className="h-4 bg-gray-700 rounded w-2/3" />
            <div className="h-3 bg-gray-800 rounded w-1/3" />
          </div>
        </div>
      </div>
    );
  }

  // 폴백: 심플한 로딩 스피너
  return (
    <div className="flex items-center justify-center h-40 bg-gray-900 rounded-md">
      <div className="spinner-border spinner-border-sm text-gray-400" role="status">
        <span className="visually-hidden">Loading...</span>
      </div>
    </div>
  );
};

export default React.memo(FileSkeleton);
