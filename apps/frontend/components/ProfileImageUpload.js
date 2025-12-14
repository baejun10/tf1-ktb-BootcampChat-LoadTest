import React, { useState, useRef, useEffect } from 'react';
import { CameraIcon, CloseOutlineIcon } from '@vapor-ui/icons';
import { Button, Text, Callout, IconButton, VStack, HStack } from '@vapor-ui/core';
import { useAuth } from '@/contexts/AuthContext';
import CustomAvatar from '@/components/CustomAvatar';
import { Toast } from '@/components/Toast';

const ProfileImageUpload = ({ currentImage, onImageChange }) => {
  const { user } = useAuth();
  const [previewUrl, setPreviewUrl] = useState(null);
  const [error, setError] = useState('');
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef(null);

  // 프로필 이미지 URL 생성
  const getProfileImageUrl = (imagePath) => {
    if (!imagePath) return null;
    return imagePath.startsWith('http') ? 
      imagePath : 
      `${process.env.NEXT_PUBLIC_API_URL}${imagePath}`;
  };

  // 컴포넌트 마운트 시 이미지 설정
  useEffect(() => {
    const imageUrl = getProfileImageUrl(currentImage);
    setPreviewUrl(imageUrl);
  }, [currentImage]);

  const handleFileSelect = async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;

    try {
      if (!file.type.startsWith('image/')) {
        throw new Error('이미지 파일만 업로드할 수 있습니다.');
      }

      if (file.size > 5 * 1024 * 1024) {
        throw new Error('파일 크기는 5MB를 초과할 수 없습니다.');
      }

      setUploading(true);
      setError('');

      const objectUrl = URL.createObjectURL(file);
      setPreviewUrl(objectUrl);

      if (!user?.token) {
        throw new Error('인증 정보가 없습니다.');
      }

      const presignResponse = await fetch(`${process.env.NEXT_PUBLIC_API_URL}/api/users/profile-image/presign`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-auth-token': user?.token,
          'x-session-id': user?.sessionId
        },
        body: JSON.stringify({
          filename: file.name,
          mimetype: file.type,
          size: file.size
        })
      });

      if (!presignResponse.ok) {
        const errorData = await presignResponse.json();
        throw new Error(errorData.message || 'Presigned URL 생성에 실패했습니다.');
      }

      const { uploadUrl, headers, uploadId } = await presignResponse.json();

      const uploadHeaders = new Headers();
      if (headers) {
        Object.entries(headers).forEach(([key, value]) => {
          uploadHeaders.set(key, value);
        });
      }
      uploadHeaders.set('Content-Type', file.type);

      await fetch(uploadUrl, {
        method: 'PUT',
        headers: uploadHeaders,
        body: file,
        credentials: 'omit'
      });

      const finalizeResponse = await fetch(`${process.env.NEXT_PUBLIC_API_URL}/api/users/profile-image/finalize`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-auth-token': user?.token,
          'x-session-id': user?.sessionId
        },
        body: JSON.stringify({ uploadId })
      });

      if (!finalizeResponse.ok) {
        const errorData = await finalizeResponse.json();
        throw new Error(errorData.message || '이미지 업로드 완료 처리에 실패했습니다.');
      }

      const data = await finalizeResponse.json();

      const updatedUser = {
        ...user,
        profileImage: data.imageUrl
      };
      localStorage.setItem('user', JSON.stringify(updatedUser));

      onImageChange(data.imageUrl);

      Toast.success('프로필 이미지가 변경되었습니다.');

      window.dispatchEvent(new Event('userProfileUpdate'));

    } catch (error) {
      console.error('Image upload error:', error);
      setError(error.message);
      setPreviewUrl(getProfileImageUrl(currentImage));

      if (previewUrl && previewUrl.startsWith('blob:')) {
        URL.revokeObjectURL(previewUrl);
      }
    } finally {
      setUploading(false);
      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }
    }
  };

  const handleRemoveImage = async () => {
    try {
      setUploading(true);
      setError('');

      // 인증 정보 확인
      if (!user?.token) {
        throw new Error('인증 정보가 없습니다.');
      }

      const response = await fetch(`${process.env.NEXT_PUBLIC_API_URL}/api/users/profile-image`, {
        method: 'DELETE',
        headers: {
          'x-auth-token': user?.token,
          'x-session-id': user?.sessionId
        }
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.message || '이미지 삭제에 실패했습니다.');
      }

      // 로컬 스토리지의 사용자 정보 업데이트
      const updatedUser = {
        ...user,
        profileImage: ''
      };
      localStorage.setItem('user', JSON.stringify(updatedUser));

      // 기존 objectUrl 정리
      if (previewUrl && previewUrl.startsWith('blob:')) {
        URL.revokeObjectURL(previewUrl);
      }

      setPreviewUrl(null);
      onImageChange('');

      // 전역 이벤트 발생
      window.dispatchEvent(new Event('userProfileUpdate'));

    } catch (error) {
      console.error('Image removal error:', error);
      setError(error.message);
    } finally {
      setUploading(false);
    }
  };

  // 컴포넌트 언마운트 시 cleanup
  useEffect(() => {
    return () => {
      if (previewUrl && previewUrl.startsWith('blob:')) {
        URL.revokeObjectURL(previewUrl);
      }
    };
  }, [previewUrl]);

  return (
    <VStack gap="$300" alignItems="center">
      <CustomAvatar
        user={user}
        size="xl"
        persistent={true}
        showInitials={true}
        data-testid="profile-image-avatar"
      />
      
      <HStack gap="$200" justifyContent="center">
        <Button
          type="button"
          onClick={() => fileInputRef.current?.click()}
          disabled={uploading}
          data-testid="profile-image-upload-button"
        >
          <CameraIcon />
          이미지 변경
        </Button>

        {previewUrl && (
          <Button
            type="button"
            variant="fill"
            colorPalette="danger"
            onClick={handleRemoveImage}
            disabled={uploading}
            data-testid="profile-image-delete-button"
          >
            <CloseOutlineIcon />
            이미지 삭제
          </Button>
        )}
      </HStack>

      <input
        ref={fileInputRef}
        type="file"
        className="hidden"
        accept="image/*"
        onChange={handleFileSelect}
        data-testid="profile-image-file-input"
      />

      {error && (
        <Callout color="danger">
          <HStack gap="$200" alignItems="center">
            <Text>{error}</Text>
          </HStack>
        </Callout>
      )}

      {uploading && (
        <Text typography="body3" color="$hint-100">
          이미지 업로드 중...
        </Text>
      )}
    </VStack>
  );
};

export default ProfileImageUpload;